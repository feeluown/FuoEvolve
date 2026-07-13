import Foundation
import Python
import Shared

final class PythonCoreBridge: NSObject, IosPythonRuntime {
    static let shared = PythonCoreBridge()

    private let queue = DispatchQueue(label: "org.feeluown.mobile.python")
    private var bridge: UnsafeMutablePointer<PyObject>?
    private var configuredProviders = ""

    func createBridge(enabledProvidersJson: String) -> String {
        queue.sync {
            do {
                try startPythonIfNeeded()
                return try withGIL {
                    if bridge != nil, configuredProviders == enabledProvidersJson {
                        return ""
                    }
                    let module = try importModule("fuo_mobile.bridge")
                    let factory = try attribute("create_bridge", of: module)
                    bridge = try invoke(factory, arguments: [enabledProvidersJson])
                    configuredProviders = enabledProvidersJson
                    return ""
                }
            } catch {
                return errorResult(error)
            }
        }
    }

    func call(method: String, arguments: [String]) -> String {
        queue.sync {
            do {
                return try withGIL {
                    guard let bridge else {
                        throw PythonBridgeError.message("Python bridge 尚未初始化")
                    }
                    let function = try attribute(method, of: bridge)
                    let result = try invoke(function, arguments: arguments)
                    guard let utf8 = PyUnicode_AsUTF8(result) else {
                        throw currentPythonError(fallback: "Python 返回值不是字符串")
                    }
                    return String(cString: utf8)
                }
            } catch {
                return errorResult(error)
            }
        }
    }

    private func startPythonIfNeeded() throws {
        if Py_IsInitialized() != 0 {
            return
        }
        guard let resources = Bundle.main.resourceURL else {
            throw PythonBridgeError.message("找不到 App 资源目录")
        }
        let pythonHome = resources.appendingPathComponent("python", isDirectory: true)
        let appPath = resources.appendingPathComponent("python-app", isDirectory: true)
        guard let userHome = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
            throw PythonBridgeError.message("找不到 App Documents 目录")
        }
        let fuoData = userHome
            .appendingPathComponent(".FeelUOwn", isDirectory: true)
            .appendingPathComponent("data", isDirectory: true)
        try FileManager.default.createDirectory(at: fuoData, withIntermediateDirectories: true)
        setenv("PYTHONHOME", pythonHome.path, 1)
        setenv("PYTHONPATH", appPath.path, 1)
        setenv("PYTHONDONTWRITEBYTECODE", "1", 1)
        setenv("FEELUOWN_USER_HOME", userHome.path, 1)
        setenv("HOME", userHome.path, 1)
        Py_Initialize()
        guard Py_IsInitialized() != 0 else {
            throw PythonBridgeError.message("CPython 初始化失败")
        }
        PyEval_SaveThread()
    }

    private func withGIL<T>(_ body: () throws -> T) rethrows -> T {
        let state = PyGILState_Ensure()
        defer { PyGILState_Release(state) }
        return try body()
    }

    private func importModule(_ name: String) throws -> UnsafeMutablePointer<PyObject> {
        guard let module = PyImport_ImportModule(name) else {
            throw currentPythonError(fallback: "无法导入 \(name)")
        }
        return module
    }

    private func attribute(
        _ name: String,
        of object: UnsafeMutablePointer<PyObject>
    ) throws -> UnsafeMutablePointer<PyObject> {
        guard let value = PyObject_GetAttrString(object, name) else {
            throw currentPythonError(fallback: "找不到 Python 方法 \(name)")
        }
        return value
    }

    private func invoke(
        _ callable: UnsafeMutablePointer<PyObject>,
        arguments: [String]
    ) throws -> UnsafeMutablePointer<PyObject> {
        guard let tuple = PyTuple_New(arguments.count) else {
            throw currentPythonError(fallback: "无法创建 Python 参数")
        }
        for (index, argument) in arguments.enumerated() {
            let value: UnsafeMutablePointer<PyObject>?
            if argument.hasPrefix("__FUO_INT__:"),
               let integer = Int64(argument.dropFirst("__FUO_INT__:".count)) {
                value = PyLong_FromLongLong(integer)
            } else if argument.hasPrefix("__FUO_BOOL__:") {
                let boolean = argument.dropFirst("__FUO_BOOL__:".count) == "true"
                value = PyBool_FromLong(boolean ? 1 : 0)
            } else if argument.hasPrefix("__FUO_DOUBLE__:"),
                      let double = Double(argument.dropFirst("__FUO_DOUBLE__:".count)) {
                value = PyFloat_FromDouble(double)
            } else {
                value = PyUnicode_FromString(argument)
            }
            guard let value else {
                throw currentPythonError(fallback: "无法编码 Python 参数")
            }
            PyTuple_SetItem(tuple, index, value)
        }
        guard let result = PyObject_CallObject(callable, tuple) else {
            throw currentPythonError(fallback: "Python 调用失败")
        }
        return result
    }

    private func currentPythonError(fallback: String) -> PythonBridgeError {
        guard PyErr_Occurred() != nil else {
            return .message(fallback)
        }
        var type: UnsafeMutablePointer<PyObject>?
        var value: UnsafeMutablePointer<PyObject>?
        var traceback: UnsafeMutablePointer<PyObject>?
        PyErr_Fetch(&type, &value, &traceback)
        PyErr_NormalizeException(&type, &value, &traceback)
        if let value, let description = PyObject_Str(value), let utf8 = PyUnicode_AsUTF8(description) {
            return .message(String(cString: utf8))
        }
        return .message(fallback)
    }

    private func errorResult(_ error: Error) -> String {
        "__FUO_PYTHON_ERROR__:\(error.localizedDescription)"
    }
}

private enum PythonBridgeError: LocalizedError {
    case message(String)

    var errorDescription: String? {
        switch self {
        case let .message(message): message
        }
    }
}
