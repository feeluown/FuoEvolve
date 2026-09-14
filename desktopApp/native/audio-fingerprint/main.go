package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"math"
	"os"

	embind "github.com/jerbob92/wazero-emscripten-embind"
	wabinbinary "github.com/tetratelabs/wabin/binary"
	wabinwasm "github.com/tetratelabs/wabin/wasm"
	"github.com/tetratelabs/wazero"
	"github.com/tetratelabs/wazero/api"
)

const expectedSelfTestFingerprint = "Oxx8fV/EFTodkOd6OGfINlloG4c6o/Pl/brdhHqA/CD/u/mJHB/w7MHuD7isMM5qQHPDgkTSuB5ibZmJuLbl5UIpsOf/RwaY3JYBIH/WviQGnAEo3+0WfrOAtljkY4X9T95hnU5gv/fVE4Tsx9Kybjv1wORt1HIG3X0NzvS8PPfj3/RylFOTa2ADTOAkuA5nNOJZaHjd36dExYZy5Uuo8QvyJhbycR/XgOqEjQuegHA23iIeZgjsKt82VjlCJSB5uwaJ6ukC//LFmAGBEqw/n2n7gLLeUa0USNEFGQVBccw="

var importNameMap = map[string]string{
	"a": "_embind_register_memory_view",
	"b": "_embind_register_integer",
	"c": "_embind_register_class_function",
	"d": "__assert_fail",
	"e": "_embind_register_std_wstring",
	"f": "__cxa_throw",
	"g": "__cxa_allocate_exception",
	"h": "abort",
	"i": "fd_write",
	"j": "_embind_register_float",
	"k": "_embind_register_std_string",
	"l": "_embind_register_function",
	"m": "_emval_decref",
	"n": "_emval_incref",
	"o": "_emval_take_value",
	"p": "_embind_register_bigint",
	"q": "setTempRet0",
	"r": "emscripten_memcpy_big",
	"s": "emscripten_resize_heap",
	"t": "strftime_l",
	"u": "environ_get",
	"v": "environ_sizes_get",
	"w": "_embind_register_class_constructor",
	"x": "_embind_register_emval",
	"y": "_embind_register_bool",
	"z": "_embind_register_void",
	"A": "_embind_register_class",
}

var exportNameMap = map[string]string{
	"B": "memory",
	"C": "__wasm_call_ctors",
	"D": "__indirect_function_table",
	"E": "malloc",
	"F": "free",
	"G": "__getTypeName",
	"H": "__embind_register_native_and_builtin_types",
}

type fingerprintRequest struct {
	SamplesBase64 string `json:"samplesBase64"`
}

type fingerprintResponse struct {
	Status      string `json:"status"`
	Fingerprint string `json:"fingerprint,omitempty"`
	Message     string `json:"message,omitempty"`
}

type fingerprintRuntime struct {
	ctx    context.Context
	engine embind.Engine
	module api.Module
	rt     wazero.Runtime
}

func main() {
	wasmPath := flag.String("wasm", "", "path to afp.wasm")
	selfTest := flag.Bool("self-test", false, "verify the fixed fingerprint vector")
	flag.Parse()

	if *wasmPath == "" {
		fail(errors.New("--wasm is required"))
	}

	runtime, err := newFingerprintRuntime(*wasmPath)
	if err != nil {
		fail(err)
	}
	defer runtime.close()

	if *selfTest {
		if err := runtime.selfTest(); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		fmt.Println("audio fingerprint runtime self-test passed")
		return
	}

	request, err := readRequest(os.Stdin)
	if err != nil {
		fail(err)
	}
	samples, err := base64.StdEncoding.DecodeString(request.SamplesBase64)
	if err != nil {
		fail(fmt.Errorf("invalid samplesBase64: %w", err))
	}
	if len(samples) == 0 || len(samples)%4 != 0 {
		fail(fmt.Errorf("audio samples must contain non-empty Float32 bytes"))
	}

	fingerprint, err := runtime.generate(samples)
	if err != nil {
		fail(err)
	}
	emit(fingerprintResponse{Status: "success", Fingerprint: fingerprint})
}

func readRequest(reader io.Reader) (fingerprintRequest, error) {
	var request fingerprintRequest
	decoder := json.NewDecoder(bufio.NewReader(reader))
	if err := decoder.Decode(&request); err != nil {
		return request, fmt.Errorf("unable to decode fingerprint request: %w", err)
	}
	if request.SamplesBase64 == "" {
		return request, errors.New("samplesBase64 is required")
	}
	return request, nil
}

func emit(response fingerprintResponse) {
	_ = json.NewEncoder(os.Stdout).Encode(response)
}

func fail(err error) {
	emit(fingerprintResponse{Status: "error", Message: err.Error()})
	os.Exit(1)
}

func newFingerprintRuntime(path string) (*fingerprintRuntime, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("unable to read afp.wasm: %w", err)
	}
	normalized, moduleDefinition, err := normalizeAfpModule(raw)
	if err != nil {
		return nil, err
	}

	ctx := context.Background()
	rt := wazero.NewRuntime(ctx)
	compiled, err := rt.CompileModule(ctx, normalized)
	if err != nil {
		_ = rt.Close(ctx)
		return nil, fmt.Errorf("unable to compile audio fingerprint wasm: %w", err)
	}

	builder := rt.NewHostModuleBuilder("env")
	if err := exportSystemImports(builder, moduleDefinition); err != nil {
		_ = rt.Close(ctx)
		return nil, err
	}

	engine := embind.CreateEngine(embind.NewConfig())
	if err := engine.NewFunctionExporterForModule(compiled).ExportFunctions(builder); err != nil {
		_ = rt.Close(ctx)
		return nil, fmt.Errorf("unable to configure Embind imports: %w", err)
	}
	if _, err := builder.Instantiate(ctx); err != nil {
		_ = rt.Close(ctx)
		return nil, fmt.Errorf("unable to instantiate audio fingerprint host imports: %w", err)
	}

	ctx = engine.Attach(ctx)
	module, err := rt.InstantiateModule(
		ctx,
		compiled,
		wazero.NewModuleConfig().WithName("").WithStartFunctions(),
	)
	if err != nil {
		_ = rt.Close(ctx)
		return nil, fmt.Errorf("unable to instantiate audio fingerprint wasm: %w", err)
	}
	ctors := module.ExportedFunction("__wasm_call_ctors")
	if ctors == nil {
		_ = rt.Close(ctx)
		return nil, errors.New("audio fingerprint wasm is missing __wasm_call_ctors")
	}
	if _, err := ctors.Call(ctx); err != nil {
		_ = rt.Close(ctx)
		return nil, fmt.Errorf("unable to initialize audio fingerprint wasm: %w", err)
	}

	return &fingerprintRuntime{ctx: ctx, engine: engine, module: module, rt: rt}, nil
}

func (r *fingerprintRuntime) close() {
	_ = r.rt.Close(r.ctx)
}

func (r *fingerprintRuntime) generate(floatBytes []byte) (string, error) {
	// The original browser wrapper passes Float32Array.buffer to an Embind
	// std::string conversion. A Go string preserves the same raw bytes, including
	// embedded NULs, so no JavaScript or browser runtime is needed here.
	result, err := r.engine.CallPublicSymbol(r.ctx, "ExtractQueryFP", string(floatBytes))
	if err != nil {
		return "", fmt.Errorf("ExtractQueryFP failed: %w", err)
	}
	vector, ok := result.(embind.ClassBase)
	if !ok {
		return "", fmt.Errorf("ExtractQueryFP returned unsupported value %T", result)
	}
	defer func() { _ = vector.DeleteInstance(r.ctx, vector) }()

	sizeValue, err := vector.CallInstanceMethod(r.ctx, vector, "size")
	if err != nil {
		return "", fmt.Errorf("unable to read fingerprint size: %w", err)
	}
	size, err := asUint32(sizeValue)
	if err != nil {
		return "", err
	}
	if size <= 64 {
		return "", fmt.Errorf("fingerprint output is unexpectedly short: %d bytes", size)
	}
	fingerprint := make([]byte, size)
	for index := uint32(0); index < size; index++ {
		value, err := vector.CallInstanceMethod(r.ctx, vector, "get", index)
		if err != nil {
			return "", fmt.Errorf("unable to read fingerprint byte %d: %w", index, err)
		}
		fingerprint[index], err = asByte(value)
		if err != nil {
			return "", fmt.Errorf("invalid fingerprint byte %d: %w", index, err)
		}
	}
	return base64.StdEncoding.EncodeToString(fingerprint), nil
}

func (r *fingerprintRuntime) selfTest() error {
	const sampleCount = 48_000
	bytes := make([]byte, sampleCount*4)
	for index := 0; index < sampleCount; index++ {
		value := float32(0.5 * math.Sin(2*math.Pi*440*float64(index)/8000))
		binary.LittleEndian.PutUint32(bytes[index*4:], math.Float32bits(value))
	}
	actual, err := r.generate(bytes)
	if err != nil {
		return err
	}
	if actual != expectedSelfTestFingerprint {
		return errors.New("fixed audio fingerprint verification mismatch")
	}
	return nil
}

func normalizeAfpModule(raw []byte) ([]byte, *wabinwasm.Module, error) {
	module, err := wabinbinary.DecodeModule(raw, wabinwasm.CoreFeaturesV2)
	if err != nil {
		return nil, nil, fmt.Errorf("unable to decode afp.wasm: %w", err)
	}
	for _, imported := range module.ImportSection {
		if imported.Module != "a" {
			continue
		}
		name, ok := importNameMap[imported.Name]
		if !ok {
			return nil, nil, fmt.Errorf("unsupported minified afp.wasm import %q", imported.Name)
		}
		imported.Module = "env"
		imported.Name = name
	}
	for _, exported := range module.ExportSection {
		if name, ok := exportNameMap[exported.Name]; ok {
			exported.Name = name
		}
	}
	return wabinbinary.EncodeModule(module), module, nil
}

func exportSystemImports(builder wazero.HostModuleBuilder, module *wabinwasm.Module) error {
	implementations := map[string]api.GoModuleFunc{
		"__assert_fail":             api.GoModuleFunc(hostAssertFail),
		"__cxa_allocate_exception": api.GoModuleFunc(hostAllocateException),
		"__cxa_throw":              api.GoModuleFunc(hostThrow),
		"abort":                    api.GoModuleFunc(hostAbort),
		"emscripten_memcpy_big":    api.GoModuleFunc(hostMemcpy),
		"emscripten_resize_heap":   api.GoModuleFunc(hostResizeHeap),
		"environ_get":              api.GoModuleFunc(hostEnvironGet),
		"environ_sizes_get":        api.GoModuleFunc(hostEnvironSizesGet),
		"fd_write":                 api.GoModuleFunc(hostFdWrite),
		"setTempRet0":              api.GoModuleFunc(hostSetTempRet0),
		"strftime_l":               api.GoModuleFunc(hostStrftime),
	}

	for _, imported := range module.ImportSection {
		if imported.Module != "env" || imported.Type != wabinwasm.ExternTypeFunc {
			continue
		}
		implementation, ok := implementations[imported.Name]
		if !ok {
			continue // Embind exports the remaining env functions.
		}
		if int(imported.DescFunc) >= len(module.TypeSection) {
			return fmt.Errorf("invalid function type for import %s", imported.Name)
		}
		functionType := module.TypeSection[imported.DescFunc]
		params, err := convertValueTypes(functionType.Params)
		if err != nil {
			return fmt.Errorf("unsupported parameters for %s: %w", imported.Name, err)
		}
		results, err := convertValueTypes(functionType.Results)
		if err != nil {
			return fmt.Errorf("unsupported results for %s: %w", imported.Name, err)
		}
		builder.NewFunctionBuilder().
			WithGoModuleFunction(implementation, params, results).
			Export(imported.Name)
	}
	return nil
}

func convertValueTypes(types []wabinwasm.ValueType) ([]api.ValueType, error) {
	result := make([]api.ValueType, len(types))
	for index, valueType := range types {
		switch valueType {
		case wabinwasm.ValueTypeI32:
			result[index] = api.ValueTypeI32
		case wabinwasm.ValueTypeI64:
			result[index] = api.ValueTypeI64
		case wabinwasm.ValueTypeF32:
			result[index] = api.ValueTypeF32
		case wabinwasm.ValueTypeF64:
			result[index] = api.ValueTypeF64
		case wabinwasm.ValueTypeExternref:
			result[index] = api.ValueTypeExternref
		case wabinwasm.ValueTypeFuncref:
			result[index] = api.ValueTypeFuncref
		default:
			return nil, fmt.Errorf("value type 0x%x", byte(valueType))
		}
	}
	return result, nil
}

func hostAssertFail(_ context.Context, mod api.Module, stack []uint64) {
	expression := readCString(mod.Memory(), uint32(stack[0]))
	file := readCString(mod.Memory(), uint32(stack[1]))
	panic(fmt.Errorf("wasm assertion failed: %s (%s)", expression, file))
}

func hostAllocateException(ctx context.Context, mod api.Module, stack []uint64) {
	size := uint32(stack[0])
	malloc := mod.ExportedFunction("malloc")
	if malloc == nil {
		panic("malloc is unavailable while allocating a C++ exception")
	}
	result, err := malloc.Call(ctx, uint64(size+16))
	if err != nil || len(result) == 0 {
		panic(fmt.Errorf("unable to allocate C++ exception: %w", err))
	}
	stack[0] = result[0] + 16
}

func hostThrow(_ context.Context, _ api.Module, stack []uint64) {
	panic(fmt.Errorf("audio fingerprint wasm threw a C++ exception at 0x%x", uint32(stack[0])))
}

func hostAbort(_ context.Context, _ api.Module, _ []uint64) {
	panic("audio fingerprint wasm aborted")
}

func hostMemcpy(_ context.Context, mod api.Module, stack []uint64) {
	destination := uint32(stack[0])
	source := uint32(stack[1])
	length := uint32(stack[2])
	data, ok := mod.Memory().Read(source, length)
	if !ok {
		panic("emscripten_memcpy_big source is outside wasm memory")
	}
	copyBuffer := append([]byte(nil), data...)
	if !mod.Memory().Write(destination, copyBuffer) {
		panic("emscripten_memcpy_big destination is outside wasm memory")
	}
	stack[0] = uint64(destination)
}

func hostResizeHeap(_ context.Context, mod api.Module, stack []uint64) {
	targetBytes := uint32(stack[0])
	currentBytes := mod.Memory().Size()
	if targetBytes <= currentBytes {
		stack[0] = 1
		return
	}
	const pageSize = uint32(64 * 1024)
	currentPages := (currentBytes + pageSize - 1) / pageSize
	targetPages := (targetBytes + pageSize - 1) / pageSize
	_, ok := mod.Memory().Grow(targetPages - currentPages)
	if ok {
		stack[0] = 1
	} else {
		stack[0] = 0
	}
}

func hostEnvironGet(_ context.Context, _ api.Module, stack []uint64) {
	stack[0] = 0
}

func hostEnvironSizesGet(_ context.Context, mod api.Module, stack []uint64) {
	if !mod.Memory().WriteUint32Le(uint32(stack[0]), 0) ||
		!mod.Memory().WriteUint32Le(uint32(stack[1]), 0) {
		panic("unable to write empty environment metadata")
	}
	stack[0] = 0
}

func hostFdWrite(_ context.Context, mod api.Module, stack []uint64) {
	fd := uint32(stack[0])
	iovs := uint32(stack[1])
	iovsLen := uint32(stack[2])
	writtenPointer := uint32(stack[3])
	var writer io.Writer = io.Discard
	if fd == 1 {
		writer = os.Stdout
	} else if fd == 2 {
		writer = os.Stderr
	}
	var total uint32
	for index := uint32(0); index < iovsLen; index++ {
		entry := iovs + index*8
		pointer, okPointer := mod.Memory().ReadUint32Le(entry)
		length, okLength := mod.Memory().ReadUint32Le(entry + 4)
		if !okPointer || !okLength {
			panic("fd_write iovec is outside wasm memory")
		}
		data, ok := mod.Memory().Read(pointer, length)
		if !ok {
			panic("fd_write data is outside wasm memory")
		}
		_, _ = writer.Write(data)
		total += length
	}
	if !mod.Memory().WriteUint32Le(writtenPointer, total) {
		panic("fd_write result pointer is outside wasm memory")
	}
	stack[0] = 0
}

func hostSetTempRet0(_ context.Context, _ api.Module, _ []uint64) {}

func hostStrftime(_ context.Context, _ api.Module, stack []uint64) {
	// Fingerprint generation never needs locale-aware time formatting. Keep the
	// Emscripten compatibility import deterministic if dead code reaches it.
	stack[0] = 0
}

func readCString(memory api.Memory, address uint32) string {
	const maxLength = 4 * 1024
	buffer := make([]byte, 0, 64)
	for index := uint32(0); index < maxLength; index++ {
		value, ok := memory.ReadByte(address + index)
		if !ok || value == 0 {
			break
		}
		buffer = append(buffer, value)
	}
	return string(buffer)
}

func asUint32(value any) (uint32, error) {
	switch number := value.(type) {
	case uint8:
		return uint32(number), nil
	case uint16:
		return uint32(number), nil
	case uint32:
		return number, nil
	case uint64:
		return uint32(number), nil
	case int8:
		return uint32(number), nil
	case int16:
		return uint32(number), nil
	case int32:
		return uint32(number), nil
	case int64:
		return uint32(number), nil
	case int:
		return uint32(number), nil
	default:
		return 0, fmt.Errorf("expected integer, got %T", value)
	}
}

func asByte(value any) (byte, error) {
	switch number := value.(type) {
	case uint8:
		return number, nil
	case uint16:
		return byte(number), nil
	case uint32:
		return byte(number), nil
	case uint64:
		return byte(number), nil
	case int8:
		return byte(number), nil
	case int16:
		return byte(number), nil
	case int32:
		return byte(number), nil
	case int64:
		return byte(number), nil
	case int:
		return byte(number), nil
	default:
		return 0, fmt.Errorf("expected integer, got %T", value)
	}
}
