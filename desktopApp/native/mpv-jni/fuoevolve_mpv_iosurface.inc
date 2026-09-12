/* macOS IOSurface producer for the Nucleus TextureView path.
 *
 * Kept as an include next to the portable JNI bridge so Windows/Linux keep compiling the same
 * translation unit without linking Apple frameworks. All Apple APIs are loaded dynamically.
 */
#if defined(__APPLE__)

typedef struct fuo_mac_gpu_api {
    void *open_gl_module;
    void *io_surface_module;
    void *core_foundation_module;

    int (*cgl_choose_pixel_format)(const int *, void **, int *);
    int (*cgl_create_context)(void *, void *, void **);
    int (*cgl_destroy_pixel_format)(void *);
    int (*cgl_destroy_context)(void *);
    int (*cgl_set_current_context)(void *);
    void *(*cgl_get_current_context)(void);
    int (*cgl_tex_image_io_surface_2d)(
        void *, fuo_gl_enum, fuo_gl_enum, fuo_gl_sizei, fuo_gl_sizei,
        fuo_gl_enum, fuo_gl_enum, void *, fuo_gl_uint
    );

    void *(*io_surface_create)(const void *);
    size_t (*io_surface_align_property)(const void *, size_t);

    void *(*cf_number_create)(const void *, long, const void *);
    void *(*cf_dictionary_create)(
        const void *, const void **, const void **, long, const void *, const void *
    );
    void (*cf_release)(const void *);

    const void *key_width;
    const void *key_height;
    const void *key_bytes_per_element;
    const void *key_bytes_per_row;
    const void *key_alloc_size;
    const void *key_pixel_format;
} fuo_mac_gpu_api;

typedef struct fuo_mac_render_context {
    mpv_render_context *mpv;
    void *cgl;
} fuo_mac_render_context;

typedef struct fuo_mac_surface_target {
    void *surface;
    fuo_gl_uint texture;
    fuo_gl_uint framebuffer;
    int width;
    int height;
    int rendered_once;
} fuo_mac_surface_target;

static fuo_mac_gpu_api global_mac_gpu_api;
static int global_mac_gpu_api_loaded = 0;

#define FUO_CGL_PFA_ACCELERATED 73
#define FUO_CGL_PFA_OPENGL_PROFILE 99
#define FUO_CGL_OGLP_VERSION_3_2_CORE 0x3200
#define FUO_CF_NUMBER_SINT64_TYPE 4
#define FUO_GL_TEXTURE_RECTANGLE 0x84F5u
#define FUO_GL_TEXTURE_BINDING_RECTANGLE 0x84F6u
#define FUO_GL_RGBA8 0x8058u
#define FUO_GL_BGRA 0x80E1u
#define FUO_GL_UNSIGNED_INT_8_8_8_8_REV 0x8367u
#define FUO_PIXEL_FORMAT_BGRA 0x42475241u

static void *fuo_dlsym_required(void *module, const char *name) {
    if (module == NULL) return NULL;
    return dlsym(module, name);
}

static const void *fuo_load_exported_cf_value(void *module, const char *name) {
    const void **slot = (const void **)fuo_dlsym_required(module, name);
    return slot == NULL ? NULL : *slot;
}

#define LOAD_MAC_PROC(field, type, module, name) \
    do { \
        global_mac_gpu_api.field = (type)(intptr_t)fuo_dlsym_required(module, name); \
        if (global_mac_gpu_api.field == NULL) return 0; \
    } while (0)

static int ensure_mac_gpu_api(void) {
    if (global_mac_gpu_api_loaded) return 1;

    global_mac_gpu_api.open_gl_module = dlopen(
        "/System/Library/Frameworks/OpenGL.framework/OpenGL",
        RTLD_LAZY | RTLD_GLOBAL
    );
    global_mac_gpu_api.io_surface_module = dlopen(
        "/System/Library/Frameworks/IOSurface.framework/IOSurface",
        RTLD_LAZY | RTLD_LOCAL
    );
    global_mac_gpu_api.core_foundation_module = dlopen(
        "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation",
        RTLD_LAZY | RTLD_LOCAL
    );
    if (global_mac_gpu_api.open_gl_module == NULL ||
        global_mac_gpu_api.io_surface_module == NULL ||
        global_mac_gpu_api.core_foundation_module == NULL) {
        return 0;
    }

    LOAD_MAC_PROC(
        cgl_choose_pixel_format,
        int (*)(const int *, void **, int *),
        global_mac_gpu_api.open_gl_module,
        "CGLChoosePixelFormat"
    );
    LOAD_MAC_PROC(
        cgl_create_context,
        int (*)(void *, void *, void **),
        global_mac_gpu_api.open_gl_module,
        "CGLCreateContext"
    );
    LOAD_MAC_PROC(
        cgl_destroy_pixel_format,
        int (*)(void *),
        global_mac_gpu_api.open_gl_module,
        "CGLDestroyPixelFormat"
    );
    LOAD_MAC_PROC(
        cgl_destroy_context,
        int (*)(void *),
        global_mac_gpu_api.open_gl_module,
        "CGLDestroyContext"
    );
    LOAD_MAC_PROC(
        cgl_set_current_context,
        int (*)(void *),
        global_mac_gpu_api.open_gl_module,
        "CGLSetCurrentContext"
    );
    LOAD_MAC_PROC(
        cgl_get_current_context,
        void *(*)(void),
        global_mac_gpu_api.open_gl_module,
        "CGLGetCurrentContext"
    );
    LOAD_MAC_PROC(
        cgl_tex_image_io_surface_2d,
        int (*)(
            void *, fuo_gl_enum, fuo_gl_enum, fuo_gl_sizei, fuo_gl_sizei,
            fuo_gl_enum, fuo_gl_enum, void *, fuo_gl_uint
        ),
        global_mac_gpu_api.open_gl_module,
        "CGLTexImageIOSurface2D"
    );

    LOAD_MAC_PROC(
        io_surface_create,
        void *(*)(const void *),
        global_mac_gpu_api.io_surface_module,
        "IOSurfaceCreate"
    );
    LOAD_MAC_PROC(
        io_surface_align_property,
        size_t (*)(const void *, size_t),
        global_mac_gpu_api.io_surface_module,
        "IOSurfaceAlignProperty"
    );
    LOAD_MAC_PROC(
        cf_number_create,
        void *(*)(const void *, long, const void *),
        global_mac_gpu_api.core_foundation_module,
        "CFNumberCreate"
    );
    LOAD_MAC_PROC(
        cf_dictionary_create,
        void *(*)(const void *, const void **, const void **, long, const void *, const void *),
        global_mac_gpu_api.core_foundation_module,
        "CFDictionaryCreate"
    );
    LOAD_MAC_PROC(
        cf_release,
        void (*)(const void *),
        global_mac_gpu_api.core_foundation_module,
        "CFRelease"
    );

    global_mac_gpu_api.key_width = fuo_load_exported_cf_value(
        global_mac_gpu_api.io_surface_module,
        "kIOSurfaceWidth"
    );
    global_mac_gpu_api.key_height = fuo_load_exported_cf_value(
        global_mac_gpu_api.io_surface_module,
        "kIOSurfaceHeight"
    );
    global_mac_gpu_api.key_bytes_per_element = fuo_load_exported_cf_value(
        global_mac_gpu_api.io_surface_module,
        "kIOSurfaceBytesPerElement"
    );
    global_mac_gpu_api.key_bytes_per_row = fuo_load_exported_cf_value(
        global_mac_gpu_api.io_surface_module,
        "kIOSurfaceBytesPerRow"
    );
    global_mac_gpu_api.key_alloc_size = fuo_load_exported_cf_value(
        global_mac_gpu_api.io_surface_module,
        "kIOSurfaceAllocSize"
    );
    global_mac_gpu_api.key_pixel_format = fuo_load_exported_cf_value(
        global_mac_gpu_api.io_surface_module,
        "kIOSurfacePixelFormat"
    );
    if (global_mac_gpu_api.key_width == NULL ||
        global_mac_gpu_api.key_height == NULL ||
        global_mac_gpu_api.key_bytes_per_element == NULL ||
        global_mac_gpu_api.key_bytes_per_row == NULL ||
        global_mac_gpu_api.key_alloc_size == NULL ||
        global_mac_gpu_api.key_pixel_format == NULL) {
        return 0;
    }

    /* Loading OpenGL globally above makes the GL entry points visible to resolve_gl_proc(). */
    if (!ensure_gl_api()) return 0;

    global_mac_gpu_api_loaded = 1;
    return 1;
}

static void *create_mac_io_surface(int width, int height) {
    if (!ensure_mac_gpu_api() || width <= 0 || height <= 0) return NULL;

    size_t bytes_per_row = global_mac_gpu_api.io_surface_align_property(
        global_mac_gpu_api.key_bytes_per_row,
        (size_t)width * 4u
    );
    size_t alloc_size = global_mac_gpu_api.io_surface_align_property(
        global_mac_gpu_api.key_alloc_size,
        bytes_per_row * (size_t)height
    );
    if (bytes_per_row == 0u || alloc_size == 0u) return NULL;

    int64_t raw_values[6] = {
        width,
        height,
        4,
        (int64_t)bytes_per_row,
        (int64_t)alloc_size,
        FUO_PIXEL_FORMAT_BGRA,
    };
    const void *keys[6] = {
        global_mac_gpu_api.key_width,
        global_mac_gpu_api.key_height,
        global_mac_gpu_api.key_bytes_per_element,
        global_mac_gpu_api.key_bytes_per_row,
        global_mac_gpu_api.key_alloc_size,
        global_mac_gpu_api.key_pixel_format,
    };
    void *numbers[6] = {NULL, NULL, NULL, NULL, NULL, NULL};
    const void *values[6] = {NULL, NULL, NULL, NULL, NULL, NULL};

    for (int i = 0; i < 6; ++i) {
        numbers[i] = global_mac_gpu_api.cf_number_create(
            NULL,
            FUO_CF_NUMBER_SINT64_TYPE,
            &raw_values[i]
        );
        if (numbers[i] == NULL) goto cleanup;
        values[i] = numbers[i];
    }

    void *dictionary = global_mac_gpu_api.cf_dictionary_create(
        NULL,
        keys,
        values,
        6,
        NULL,
        NULL
    );
    if (dictionary == NULL) goto cleanup;
    void *surface = global_mac_gpu_api.io_surface_create(dictionary);
    global_mac_gpu_api.cf_release(dictionary);
    for (int i = 0; i < 6; ++i) global_mac_gpu_api.cf_release(numbers[i]);
    return surface;

cleanup:
    for (int i = 0; i < 6; ++i) {
        if (numbers[i] != NULL) global_mac_gpu_api.cf_release(numbers[i]);
    }
    return NULL;
}

static int with_mac_context(fuo_mac_render_context *renderer, void **previous) {
    if (renderer == NULL || renderer->cgl == NULL || !ensure_mac_gpu_api()) return 0;
    *previous = global_mac_gpu_api.cgl_get_current_context();
    return global_mac_gpu_api.cgl_set_current_context(renderer->cgl) == 0;
}

static void restore_mac_context(void *previous) {
    if (global_mac_gpu_api_loaded) {
        global_mac_gpu_api.cgl_set_current_context(previous);
    }
}

static void destroy_mac_surface_target(
    fuo_mac_render_context *renderer,
    fuo_mac_surface_target *target
) {
    if (target == NULL) return;
    void *previous = NULL;
    if (renderer != NULL && with_mac_context(renderer, &previous)) {
        if (target->framebuffer != 0u) {
            global_gl_api.delete_framebuffers(1, &target->framebuffer);
            target->framebuffer = 0u;
        }
        if (target->texture != 0u) {
            global_gl_api.delete_textures(1, &target->texture);
            target->texture = 0u;
        }
        restore_mac_context(previous);
    }
    if (target->surface != NULL) {
        global_mac_gpu_api.cf_release(target->surface);
        target->surface = NULL;
    }
    free(target);
}

JNIEXPORT jlong JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeCreateIoSurfaceRenderContext(
    JNIEnv *env,
    jobject self,
    jlong handle_value
) {
    (void)env;
    (void)self;
    mpv_handle *handle = handle_from_jlong(handle_value);
    if (handle == NULL || !ensure_mac_gpu_api()) return 0;

    int attributes[] = {
        FUO_CGL_PFA_OPENGL_PROFILE,
        FUO_CGL_OGLP_VERSION_3_2_CORE,
        FUO_CGL_PFA_ACCELERATED,
        0,
    };
    void *pixel_format = NULL;
    int pixel_format_count = 0;
    if (global_mac_gpu_api.cgl_choose_pixel_format(
            attributes,
            &pixel_format,
            &pixel_format_count
        ) != 0 || pixel_format == NULL || pixel_format_count <= 0) {
        return 0;
    }

    void *cgl = NULL;
    int create_result = global_mac_gpu_api.cgl_create_context(pixel_format, NULL, &cgl);
    global_mac_gpu_api.cgl_destroy_pixel_format(pixel_format);
    if (create_result != 0 || cgl == NULL) return 0;

    fuo_mac_render_context *renderer =
        (fuo_mac_render_context *)calloc(1u, sizeof(fuo_mac_render_context));
    if (renderer == NULL) {
        global_mac_gpu_api.cgl_destroy_context(cgl);
        return 0;
    }
    renderer->cgl = cgl;

    void *previous = global_mac_gpu_api.cgl_get_current_context();
    if (global_mac_gpu_api.cgl_set_current_context(cgl) != 0) goto failure;

    mpv_opengl_init_params gl_init = {
        .get_proc_address = resolve_gl_proc,
        .get_proc_address_ctx = NULL,
    };
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_API_TYPE, (void *)MPV_RENDER_API_TYPE_OPENGL},
        {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init},
        {MPV_RENDER_PARAM_INVALID, NULL},
    };
    int mpv_result = mpv_render_context_create(&renderer->mpv, handle, params);
    restore_mac_context(previous);
    if (mpv_result < 0 || renderer->mpv == NULL) goto failure_without_current;
    return (jlong)(intptr_t)renderer;

failure:
    restore_mac_context(previous);
failure_without_current:
    if (renderer->mpv != NULL) mpv_render_context_free(renderer->mpv);
    global_mac_gpu_api.cgl_destroy_context(renderer->cgl);
    free(renderer);
    return 0;
}

JNIEXPORT jlong JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeCreateIoSurfaceRenderTarget(
    JNIEnv *env,
    jobject self,
    jlong render_context_value,
    jint width,
    jint height
) {
    (void)env;
    (void)self;
    fuo_mac_render_context *renderer =
        (fuo_mac_render_context *)(intptr_t)render_context_value;
    if (renderer == NULL || width <= 0 || height <= 0) return 0;

    void *previous = NULL;
    if (!with_mac_context(renderer, &previous)) return 0;

    fuo_gl_int previous_framebuffer = 0;
    fuo_gl_int previous_texture = 0;
    global_gl_api.get_integerv(FUO_GL_FRAMEBUFFER_BINDING, &previous_framebuffer);
    global_gl_api.get_integerv(FUO_GL_TEXTURE_BINDING_RECTANGLE, &previous_texture);

    fuo_mac_surface_target *target =
        (fuo_mac_surface_target *)calloc(1u, sizeof(fuo_mac_surface_target));
    if (target == NULL) goto failure_no_target;
    target->width = width;
    target->height = height;
    target->surface = create_mac_io_surface(width, height);
    if (target->surface == NULL) goto failure;

    global_gl_api.gen_textures(1, &target->texture);
    if (target->texture == 0u) goto failure;
    global_gl_api.bind_texture(FUO_GL_TEXTURE_RECTANGLE, target->texture);
    global_gl_api.tex_parameteri(
        FUO_GL_TEXTURE_RECTANGLE,
        FUO_GL_TEXTURE_MIN_FILTER,
        FUO_GL_LINEAR
    );
    global_gl_api.tex_parameteri(
        FUO_GL_TEXTURE_RECTANGLE,
        FUO_GL_TEXTURE_MAG_FILTER,
        FUO_GL_LINEAR
    );
    global_gl_api.tex_parameteri(
        FUO_GL_TEXTURE_RECTANGLE,
        FUO_GL_TEXTURE_WRAP_S,
        FUO_GL_CLAMP_TO_EDGE
    );
    global_gl_api.tex_parameteri(
        FUO_GL_TEXTURE_RECTANGLE,
        FUO_GL_TEXTURE_WRAP_T,
        FUO_GL_CLAMP_TO_EDGE
    );

    if (global_mac_gpu_api.cgl_tex_image_io_surface_2d(
            renderer->cgl,
            FUO_GL_TEXTURE_RECTANGLE,
            FUO_GL_RGBA8,
            width,
            height,
            FUO_GL_BGRA,
            FUO_GL_UNSIGNED_INT_8_8_8_8_REV,
            target->surface,
            0u
        ) != 0) {
        goto failure;
    }

    global_gl_api.gen_framebuffers(1, &target->framebuffer);
    if (target->framebuffer == 0u) goto failure;
    global_gl_api.bind_framebuffer(FUO_GL_FRAMEBUFFER, target->framebuffer);
    global_gl_api.framebuffer_texture_2d(
        FUO_GL_FRAMEBUFFER,
        FUO_GL_COLOR_ATTACHMENT0,
        FUO_GL_TEXTURE_RECTANGLE,
        target->texture,
        0
    );
    if (global_gl_api.check_framebuffer_status(FUO_GL_FRAMEBUFFER) != FUO_GL_FRAMEBUFFER_COMPLETE) {
        goto failure;
    }

    global_gl_api.bind_framebuffer(FUO_GL_FRAMEBUFFER, (fuo_gl_uint)previous_framebuffer);
    global_gl_api.bind_texture(FUO_GL_TEXTURE_RECTANGLE, (fuo_gl_uint)previous_texture);
    restore_mac_context(previous);
    return (jlong)(intptr_t)target;

failure:
    global_gl_api.bind_framebuffer(FUO_GL_FRAMEBUFFER, (fuo_gl_uint)previous_framebuffer);
    global_gl_api.bind_texture(FUO_GL_TEXTURE_RECTANGLE, (fuo_gl_uint)previous_texture);
    restore_mac_context(previous);
    destroy_mac_surface_target(renderer, target);
    return 0;

failure_no_target:
    global_gl_api.bind_framebuffer(FUO_GL_FRAMEBUFFER, (fuo_gl_uint)previous_framebuffer);
    global_gl_api.bind_texture(FUO_GL_TEXTURE_RECTANGLE, (fuo_gl_uint)previous_texture);
    restore_mac_context(previous);
    return 0;
}

JNIEXPORT jlong JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeIoSurfaceRenderTargetPointer(
    JNIEnv *env,
    jobject self,
    jlong render_target_value
) {
    (void)env;
    (void)self;
    fuo_mac_surface_target *target =
        (fuo_mac_surface_target *)(intptr_t)render_target_value;
    return target == NULL ? 0 : (jlong)(intptr_t)target->surface;
}

JNIEXPORT jboolean JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeRenderIoSurface(
    JNIEnv *env,
    jobject self,
    jlong render_context_value,
    jlong render_target_value
) {
    (void)env;
    (void)self;
    fuo_mac_render_context *renderer =
        (fuo_mac_render_context *)(intptr_t)render_context_value;
    fuo_mac_surface_target *target =
        (fuo_mac_surface_target *)(intptr_t)render_target_value;
    if (renderer == NULL || renderer->mpv == NULL || target == NULL) return JNI_FALSE;

    void *previous = NULL;
    if (!with_mac_context(renderer, &previous)) return JNI_FALSE;

    uint64_t updates = mpv_render_context_update(renderer->mpv);
    if (target->rendered_once && (updates & MPV_RENDER_UPDATE_FRAME) == 0u) {
        restore_mac_context(previous);
        return JNI_FALSE;
    }

    mpv_opengl_fbo fbo = {
        .fbo = (int)target->framebuffer,
        .w = target->width,
        .h = target->height,
        .internal_format = 0,
    };
    int flip_y = 1;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
        {MPV_RENDER_PARAM_FLIP_Y, &flip_y},
        {MPV_RENDER_PARAM_INVALID, NULL},
    };
    mpv_render_context_render(renderer->mpv, params);
    global_gl_api.flush();
    mpv_render_context_report_swap(renderer->mpv);
    target->rendered_once = 1;
    restore_mac_context(previous);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeDestroyIoSurfaceRenderTarget(
    JNIEnv *env,
    jobject self,
    jlong render_context_value,
    jlong render_target_value
) {
    (void)env;
    (void)self;
    fuo_mac_render_context *renderer =
        (fuo_mac_render_context *)(intptr_t)render_context_value;
    fuo_mac_surface_target *target =
        (fuo_mac_surface_target *)(intptr_t)render_target_value;
    destroy_mac_surface_target(renderer, target);
}

JNIEXPORT void JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeFreeIoSurfaceRenderContext(
    JNIEnv *env,
    jobject self,
    jlong render_context_value
) {
    (void)env;
    (void)self;
    fuo_mac_render_context *renderer =
        (fuo_mac_render_context *)(intptr_t)render_context_value;
    if (renderer == NULL) return;

    void *previous = NULL;
    if (with_mac_context(renderer, &previous)) {
        if (renderer->mpv != NULL) {
            mpv_render_context_free(renderer->mpv);
            renderer->mpv = NULL;
        }
        restore_mac_context(previous);
    } else if (renderer->mpv != NULL) {
        /* mpv teardown is still preferable to leaking if CGL already became unavailable. */
        mpv_render_context_free(renderer->mpv);
        renderer->mpv = NULL;
    }
    if (renderer->cgl != NULL) {
        global_mac_gpu_api.cgl_destroy_context(renderer->cgl);
        renderer->cgl = NULL;
    }
    free(renderer);
}

#else

JNIEXPORT jlong JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeCreateIoSurfaceRenderContext(
    JNIEnv *env, jobject self, jlong handle_value
) {
    (void)env; (void)self; (void)handle_value;
    return 0;
}

JNIEXPORT jlong JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeCreateIoSurfaceRenderTarget(
    JNIEnv *env, jobject self, jlong render_context_value, jint width, jint height
) {
    (void)env; (void)self; (void)render_context_value; (void)width; (void)height;
    return 0;
}

JNIEXPORT jlong JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeIoSurfaceRenderTargetPointer(
    JNIEnv *env, jobject self, jlong render_target_value
) {
    (void)env; (void)self; (void)render_target_value;
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeRenderIoSurface(
    JNIEnv *env, jobject self, jlong render_context_value, jlong render_target_value
) {
    (void)env; (void)self; (void)render_context_value; (void)render_target_value;
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeDestroyIoSurfaceRenderTarget(
    JNIEnv *env, jobject self, jlong render_context_value, jlong render_target_value
) {
    (void)env; (void)self; (void)render_context_value; (void)render_target_value;
}

JNIEXPORT void JNICALL
Java_org_feeluown_mobile_DesktopJniMpvVideoApi_nativeFreeIoSurfaceRenderContext(
    JNIEnv *env, jobject self, jlong render_context_value
) {
    (void)env; (void)self; (void)render_context_value;
}

#endif
