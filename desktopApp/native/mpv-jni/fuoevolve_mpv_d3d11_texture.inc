/* Windows D3D11 shared-texture producer for the Nucleus TextureView path.
 *
 * The libmpv software renderer already runs on a background thread in the Kotlin controller.
 * This helper owns a separate D3D11 device and a keyed-mutex shared RGBA8 texture. Kotlin reads
 * each CPU-backed ImageBitmap into a reusable ARGB IntArray; this file converts the packed ARGB
 * values to RGBA bytes in place and uploads them without involving Tao's ANGLE context.
 */
#if defined(_WIN32)

#ifndef COBJMACROS
#define COBJMACROS
#endif
#include <d3d11.h>
#include <dxgi.h>

typedef HRESULT (WINAPI *fuo_d3d11_create_device_fn)(
    IDXGIAdapter *,
    D3D_DRIVER_TYPE,
    HMODULE,
    UINT,
    const D3D_FEATURE_LEVEL *,
    UINT,
    UINT,
    ID3D11Device **,
    D3D_FEATURE_LEVEL *,
    ID3D11DeviceContext **
);

typedef struct fuo_windows_video_texture {
    ID3D11Device *device;
    ID3D11DeviceContext *context;
    ID3D11Texture2D *texture;
    IDXGIKeyedMutex *keyed_mutex;
    HANDLE shared_handle;
    int width;
    int height;
} fuo_windows_video_texture;

/* {035f3ab4-482e-4e50-b41f-8a7f8bd8960b} */
static const GUID fuo_iid_idxgi_resource =
    {0x035f3ab4, 0x482e, 0x4e50, {0xb4, 0x1f, 0x8a, 0x7f, 0x8b, 0xd8, 0x96, 0x0b}};
/* {9d8e1289-d7b3-465f-8126-250e349af85d} */
static const GUID fuo_iid_idxgi_keyed_mutex =
    {0x9d8e1289, 0xd7b3, 0x465f, {0x81, 0x26, 0x25, 0x0e, 0x34, 0x9a, 0xf8, 0x5d}};

static fuo_d3d11_create_device_fn fuo_resolve_d3d11_create_device(void) {
    static HMODULE module = NULL;
    static fuo_d3d11_create_device_fn create_device = NULL;
    if (create_device != NULL) return create_device;
    if (module == NULL) {
        module = GetModuleHandleA("d3d11.dll");
        if (module == NULL) module = LoadLibraryA("d3d11.dll");
    }
    if (module == NULL) return NULL;
    create_device = (fuo_d3d11_create_device_fn)(intptr_t)
        GetProcAddress(module, "D3D11CreateDevice");
    return create_device;
}

static void fuo_destroy_windows_video_texture(fuo_windows_video_texture *target) {
    if (target == NULL) return;
    if (target->keyed_mutex != NULL) {
        IDXGIKeyedMutex_Release(target->keyed_mutex);
        target->keyed_mutex = NULL;
    }
    if (target->texture != NULL) {
        ID3D11Texture2D_Release(target->texture);
        target->texture = NULL;
    }
    if (target->context != NULL) {
        ID3D11DeviceContext_Release(target->context);
        target->context = NULL;
    }
    if (target->device != NULL) {
        ID3D11Device_Release(target->device);
        target->device = NULL;
    }
    free(target);
}

static int fuo_create_d3d11_device(
    ID3D11Device **device,
    ID3D11DeviceContext **context
) {
    fuo_d3d11_create_device_fn create_device = fuo_resolve_d3d11_create_device();
    if (create_device == NULL) return 0;

    const D3D_FEATURE_LEVEL levels[] = {
        D3D_FEATURE_LEVEL_11_0,
        D3D_FEATURE_LEVEL_10_1,
        D3D_FEATURE_LEVEL_10_0,
    };
    D3D_FEATURE_LEVEL selected = D3D_FEATURE_LEVEL_10_0;
    HRESULT result = create_device(
        NULL,
        D3D_DRIVER_TYPE_HARDWARE,
        NULL,
        D3D11_CREATE_DEVICE_BGRA_SUPPORT,
        levels,
        (UINT)(sizeof(levels) / sizeof(levels[0])),
        D3D11_SDK_VERSION,
        device,
        &selected,
        context
    );
    if (SUCCEEDED(result) && *device != NULL && *context != NULL) return 1;

    if (*context != NULL) {
        ID3D11DeviceContext_Release(*context);
        *context = NULL;
    }
    if (*device != NULL) {
        ID3D11Device_Release(*device);
        *device = NULL;
    }
    result = create_device(
        NULL,
        D3D_DRIVER_TYPE_WARP,
        NULL,
        D3D11_CREATE_DEVICE_BGRA_SUPPORT,
        levels,
        (UINT)(sizeof(levels) / sizeof(levels[0])),
        D3D11_SDK_VERSION,
        device,
        &selected,
        context
    );
    return SUCCEEDED(result) && *device != NULL && *context != NULL;
}

JNIEXPORT jlong JNICALL
Java_org_feeluown_mobile_nucleus_WindowsD3D11VideoTextureApi_nativeCreate(
    JNIEnv *env,
    jclass clazz,
    jint width,
    jint height
) {
    (void)env;
    (void)clazz;
    if (width <= 0 || height <= 0) return 0;

    fuo_windows_video_texture *target =
        (fuo_windows_video_texture *)calloc(1u, sizeof(fuo_windows_video_texture));
    if (target == NULL) return 0;
    target->width = width;
    target->height = height;
    if (!fuo_create_d3d11_device(&target->device, &target->context)) goto failure;

    D3D11_TEXTURE2D_DESC desc;
    memset(&desc, 0, sizeof(desc));
    desc.Width = (UINT)width;
    desc.Height = (UINT)height;
    desc.MipLevels = 1;
    desc.ArraySize = 1;
    desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    desc.SampleDesc.Count = 1;
    desc.Usage = D3D11_USAGE_DEFAULT;
    desc.BindFlags = D3D11_BIND_SHADER_RESOURCE | D3D11_BIND_RENDER_TARGET;
    desc.MiscFlags = D3D11_RESOURCE_MISC_SHARED_KEYEDMUTEX;
    if (FAILED(ID3D11Device_CreateTexture2D(target->device, &desc, NULL, &target->texture)) ||
        target->texture == NULL) {
        goto failure;
    }

    if (FAILED(ID3D11Texture2D_QueryInterface(
            target->texture,
            &fuo_iid_idxgi_keyed_mutex,
            (void **)&target->keyed_mutex
        )) || target->keyed_mutex == NULL) {
        goto failure;
    }

    IDXGIResource *resource = NULL;
    if (FAILED(ID3D11Texture2D_QueryInterface(
            target->texture,
            &fuo_iid_idxgi_resource,
            (void **)&resource
        )) || resource == NULL) {
        goto failure;
    }
    HRESULT shared_result = IDXGIResource_GetSharedHandle(resource, &target->shared_handle);
    IDXGIResource_Release(resource);
    if (FAILED(shared_result) || target->shared_handle == NULL) goto failure;

    return (jlong)(intptr_t)target;

failure:
    fuo_destroy_windows_video_texture(target);
    return 0;
}

JNIEXPORT jlong JNICALL
Java_org_feeluown_mobile_nucleus_WindowsD3D11VideoTextureApi_nativeSharedHandle(
    JNIEnv *env,
    jclass clazz,
    jlong target_value
) {
    (void)env;
    (void)clazz;
    fuo_windows_video_texture *target =
        (fuo_windows_video_texture *)(intptr_t)target_value;
    return target == NULL ? 0 : (jlong)(intptr_t)target->shared_handle;
}

JNIEXPORT jboolean JNICALL
Java_org_feeluown_mobile_nucleus_WindowsD3D11VideoTextureApi_nativeUpload(
    JNIEnv *env,
    jclass clazz,
    jlong target_value,
    jintArray pixels_value
) {
    (void)clazz;
    fuo_windows_video_texture *target =
        (fuo_windows_video_texture *)(intptr_t)target_value;
    if (target == NULL || target->texture == NULL || target->context == NULL ||
        target->keyed_mutex == NULL || pixels_value == NULL) {
        return JNI_FALSE;
    }

    int64_t pixel_count = (int64_t)target->width * (int64_t)target->height;
    if (pixel_count <= 0 || pixel_count > (*env)->GetArrayLength(env, pixels_value)) {
        return JNI_FALSE;
    }

    jint *pixels = (*env)->GetIntArrayElements(env, pixels_value, NULL);
    if (pixels == NULL) return JNI_FALSE;

    /* Compose readPixels returns packed 0xAARRGGBB. Convert in place to an integer whose
     * little-endian byte layout is R,G,B,A for DXGI_FORMAT_R8G8B8A8_UNORM. */
    for (int64_t i = 0; i < pixel_count; ++i) {
        uint32_t argb = (uint32_t)pixels[i];
        pixels[i] = (jint)(
            (argb & 0xFF000000u) |
            ((argb & 0x00FF0000u) >> 16u) |
            (argb & 0x0000FF00u) |
            ((argb & 0x000000FFu) << 16u)
        );
    }

    HRESULT acquire = IDXGIKeyedMutex_AcquireSync(target->keyed_mutex, 0u, 16u);
    if (acquire != S_OK) {
        (*env)->ReleaseIntArrayElements(env, pixels_value, pixels, JNI_ABORT);
        return JNI_FALSE;
    }

    ID3D11DeviceContext_UpdateSubresource(
        target->context,
        (ID3D11Resource *)target->texture,
        0u,
        NULL,
        pixels,
        (UINT)target->width * 4u,
        0u
    );
    ID3D11DeviceContext_Flush(target->context);
    IDXGIKeyedMutex_ReleaseSync(target->keyed_mutex, 0u);
    (*env)->ReleaseIntArrayElements(env, pixels_value, pixels, JNI_ABORT);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_feeluown_mobile_nucleus_WindowsD3D11VideoTextureApi_nativeDestroy(
    JNIEnv *env,
    jclass clazz,
    jlong target_value
) {
    (void)env;
    (void)clazz;
    fuo_destroy_windows_video_texture(
        (fuo_windows_video_texture *)(intptr_t)target_value
    );
}

#else

JNIEXPORT jlong JNICALL
Java_org_feeluown_mobile_nucleus_WindowsD3D11VideoTextureApi_nativeCreate(
    JNIEnv *env, jclass clazz, jint width, jint height
) {
    (void)env; (void)clazz; (void)width; (void)height;
    return 0;
}

JNIEXPORT jlong JNICALL
Java_org_feeluown_mobile_nucleus_WindowsD3D11VideoTextureApi_nativeSharedHandle(
    JNIEnv *env, jclass clazz, jlong target_value
) {
    (void)env; (void)clazz; (void)target_value;
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_org_feeluown_mobile_nucleus_WindowsD3D11VideoTextureApi_nativeUpload(
    JNIEnv *env, jclass clazz, jlong target_value, jintArray pixels_value
) {
    (void)env; (void)clazz; (void)target_value; (void)pixels_value;
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_feeluown_mobile_nucleus_WindowsD3D11VideoTextureApi_nativeDestroy(
    JNIEnv *env, jclass clazz, jlong target_value
) {
    (void)env; (void)clazz; (void)target_value;
}

#endif
