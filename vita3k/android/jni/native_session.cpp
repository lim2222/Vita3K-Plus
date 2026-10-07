// Vita3K emulator project
// Copyright (C) 2026 Vita3K team
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.

#include "android_state.h"

#include <app/functions.h>
#include <ime/keyboard.h>
#include <io/state.h>
#include <motion/functions.h>
#include <renderer/texture_cache.h>

#include <SDL3/SDL_events.h>
#include <jni.h>

#include <algorithm>

namespace {

app::AppSessionPauseReason to_pause_reason(const jint reason_mask) {
    switch (static_cast<uint32_t>(reason_mask)) {
    case static_cast<uint32_t>(app::AppSessionPauseReason::User):
        return app::AppSessionPauseReason::User;
    case static_cast<uint32_t>(app::AppSessionPauseReason::Menu):
        return app::AppSessionPauseReason::Menu;
    case static_cast<uint32_t>(app::AppSessionPauseReason::Background):
        return app::AppSessionPauseReason::Background;
    default:
        return app::AppSessionPauseReason::None;
    }
}

std::vector<std::string> read_string_array(JNIEnv *env, jobjectArray array) {
    std::vector<std::string> values;
    if (!array)
        return values;

    const jsize count = env->GetArrayLength(array);
    values.reserve(static_cast<size_t>(std::max<jsize>(count, 0)));
    for (jsize index = 0; index < count; ++index) {
        auto *value = static_cast<jstring>(env->GetObjectArrayElement(array, index));
        if (!value) {
            values.emplace_back();
            continue;
        }

        values.push_back(jstring_to_string(env, value));
        env->DeleteLocalRef(value);
    }

    return values;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_setInputIntercepted(JNIEnv *, jclass, jboolean intercepted) {
    auto *controller = get_app_session_controller();
    if (!controller || !controller->is_running())
        return JNI_FALSE;

    return controller->set_input_intercepted(intercepted == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_setPauseReasonEnabled(JNIEnv *, jclass, jint reason_mask, jboolean enabled) {
    auto *controller = get_app_session_controller();
    const auto reason = to_pause_reason(reason_mask);
    if (!controller || reason == app::AppSessionPauseReason::None || !controller->is_running())
        return JNI_FALSE;

    return controller->set_pause_reason(reason, enabled == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_isAppRunning(JNIEnv *, jclass) {
    auto *controller = get_app_session_controller();
    return controller && controller->has_active_session() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_vita3k_emulator_Emulator_setNativeDisplayRotation(JNIEnv *, jobject, jint rotation) {
    auto *emuenv = get_emuenv();
    if (!emuenv)
        return;

    set_display_rotation(emuenv->motion, rotation);

    if (emuenv->renderer) {
        emuenv->renderer->set_device_native_rotation(rotation);
    }
}

JNIEXPORT void JNICALL
Java_org_vita3k_emulator_Emulator_setScreenOrientation(JNIEnv *, jobject, jint orientation) {
    auto *emuenv = get_emuenv();
    if (!emuenv || !emuenv->renderer)
        return;

    emuenv->renderer->set_screen_orientation(orientation);
}

JNIEXPORT void JNICALL
Java_org_vita3k_emulator_Emulator_setPortraitYOffset(JNIEnv *, jobject, jfloat offset) {
    auto *emuenv = get_emuenv();
    if (!emuenv || !emuenv->renderer)
        return;

    emuenv->renderer->set_portrait_y_offset(offset);
}

JNIEXPORT void JNICALL
Java_org_vita3k_emulator_Emulator_applyRendererConfigNative(JNIEnv *, jobject) {
    auto *emuenv = get_emuenv();
    if (!emuenv || !emuenv->renderer)
        return;

    const auto &cc = emuenv->cfg.current_config;
    auto &r = *emuenv->renderer;
    r.res_multiplier = cc.resolution_multiplier;
    r.set_surface_sync_state(cc.disable_surface_sync);
    r.set_screen_filter(cc.screen_filter);
    r.set_anisotropic_filtering(cc.anisotropic_filtering);
    r.set_stretch_display(cc.stretch_the_display_area);
    r.stretch_hd_pixel_perfect(cc.fullscreen_hd_res_pixel_perfect);
    r.set_screen_orientation(cc.screen_orientation);
    r.set_portrait_y_offset(cc.portrait_y_offset);
    r.set_device_native_rotation(emuenv->motion.device_native_rotation);
    r.set_async_compilation(cc.async_pipeline_compilation);
    r.get_texture_cache()->set_replacement_state(cc.import_textures, cc.export_textures, cc.export_as_png);
    r.common_dialog = &emuenv->common_dialog;
    r.sys_date_format = cc.sys_date_format;
    r.sys_lang = cc.sys_lang;
    r.sys_button = cc.sys_button;
}

JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_requestAppQuit(JNIEnv *, jclass) {
    auto *controller = get_app_session_controller();
    if (!controller || !controller->has_active_session())
        return JNI_FALSE;

    SDL_Event quit_event{};
    quit_event.type = SDL_EVENT_QUIT;
    return SDL_PushEvent(&quit_event) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_requestAppRelaunch(JNIEnv *env, jclass, jstring title_id_str,
    jstring self_path_str, jobjectArray args_array, jboolean load_exec_reason) {
    auto *emuenv = get_emuenv();
    auto *controller = get_app_session_controller();
    if (!emuenv || !controller || !controller->has_active_session())
        return JNI_FALSE;

    AppLaunchRequest launch_request{
        .app_path = title_id_str ? jstring_to_string(env, title_id_str) : emuenv->io.app_path,
        .self_path = self_path_str ? jstring_to_string(env, self_path_str) : std::string(),
        .argv = read_string_array(env, args_array),
        .reason = load_exec_reason == JNI_TRUE ? AppLaunchReason::LoadExec : AppLaunchReason::User
    };

    if (launch_request.app_path.empty())
        launch_request.app_path = emuenv->io.app_path;

    app::request_in_process_launch(*emuenv, std::move(launch_request));

    SDL_Event quit_event{};
    quit_event.type = SDL_EVENT_QUIT;
    return SDL_PushEvent(&quit_event) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_isAppPaused(JNIEnv *, jclass) {
    auto *controller = get_app_session_controller();
    if (!controller || !controller->is_running())
        return JNI_FALSE;

    return controller->is_paused() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_org_vita3k_emulator_NativeLib_getRunningAppTitle(JNIEnv *env, jclass) {
    auto *emuenv = get_emuenv();
    auto *controller = get_app_session_controller();
    if (!emuenv || !controller || !controller->is_running())
        return env->NewStringUTF("");

    return env->NewStringUTF(emuenv->current_app_title.c_str());
}

JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_isImeActive(JNIEnv *, jclass) {
    auto *emuenv = get_emuenv();
    auto *controller = get_app_session_controller();
    if (!emuenv || !controller || !controller->is_running())
        return JNI_FALSE;

    return is_any_ime_active(*emuenv) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_submitIme(JNIEnv *, jclass) {
    auto *emuenv = get_emuenv();
    auto *controller = get_app_session_controller();
    if (!emuenv || !controller || !controller->is_running())
        return JNI_FALSE;

    const bool submitted = submit_current_ime(*emuenv);
    if (submitted)
        ime::notify_ime_state_changed();
    return submitted ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_dismissIme(JNIEnv *, jclass) {
    auto *emuenv = get_emuenv();
    auto *controller = get_app_session_controller();
    if (!emuenv || !controller || !controller->is_running())
        return JNI_FALSE;

    const bool dismissed = dismiss_current_ime(*emuenv);
    if (dismissed)
        ime::notify_ime_state_changed();
    return dismissed ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
