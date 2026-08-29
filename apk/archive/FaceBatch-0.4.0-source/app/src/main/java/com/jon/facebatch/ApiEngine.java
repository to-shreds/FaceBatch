package com.jon.facebatch;

import android.content.Context;

import java.io.File;

public final class ApiEngine {
    private ApiEngine() {
    }

    public static File execute(Context context, SwapJob job, AppSettings.Snapshot settings,
                               File workingDirectory, ApiClient.Cancellation cancellation,
                               ApiClient.ConnectionMonitor monitor) throws Exception {
        String profile = settings.profile == null ? AppSettings.PROFILE_AIFACE : settings.profile;
        if (AppSettings.PROFILE_AIFACE.equals(profile)) {
            remember(context, "AIFaceSwap / High Quality (direct)");
            return AIFaceSwapApiClient.execute(context, job, workingDirectory, cancellation, monitor);
        }
        if (AppSettings.PROFILE_FJOY.equals(profile)) {
            remember(context, "FJoy / Magicut (direct)");
            return FJoyApiClient.execute(context, job, workingDirectory, cancellation, monitor);
        }
        if (AppSettings.PROFILE_TAO.equals(profile)) {
            remember(context, "TaoAnhDep (direct)");
            return ApiClient.execute(context, job, AppSettings.taoSnapshot(settings), workingDirectory,
                    cancellation, monitor);
        }
        if (AppSettings.PROFILE_AUTO.equals(profile)) {
            return executeAuto(context, job, settings, workingDirectory, cancellation, monitor);
        }
        remember(context, AppSettings.PROFILE_PUBLIC_KEY.equals(profile)
                ? "TiemAnhAI public API" : "Custom API");
        return ApiClient.execute(context, job, settings, workingDirectory, cancellation, monitor);
    }

    private static File executeAuto(Context context, SwapJob job, AppSettings.Snapshot settings,
                                    File workingDirectory, ApiClient.Cancellation cancellation,
                                    ApiClient.ConnectionMonitor monitor) throws Exception {
        int route;
        try {
            route = FaceOverRouter.singleRoute(context);
        } catch (Exception routingFailure) {
            remember(context, "AIFaceSwap / High Quality (routing fallback)");
            return AIFaceSwapApiClient.execute(context, job, workingDirectory, cancellation, monitor);
        }

        AppSettings.prefs(context).edit()
                .putString("last_faceover_route", String.valueOf(route))
                .putString("last_faceover_route_label", FaceOverRouter.routeLabel(route))
                .apply();

        if (route == 0) {
            remember(context, "AIFaceSwap / High Quality via Face Over Auto");
            return AIFaceSwapApiClient.execute(context, job, workingDirectory, cancellation, monitor);
        }
        if (route == 3) {
            remember(context, "TaoAnhDep via Face Over Auto");
            try {
                return ApiClient.execute(context, job, AppSettings.taoSnapshot(settings), workingDirectory,
                        cancellation, monitor);
            } catch (ApiClient.ApiException taoFailure) {
                if (taoFailure.httpStatus == 429 || taoFailure.httpStatus >= 500) {
                    remember(context, "AIFaceSwap / High Quality (Auto fallback after Tao limit)");
                    return AIFaceSwapApiClient.execute(context, job, workingDirectory,
                            cancellation, monitor);
                }
                throw taoFailure;
            }
        }
        if (route == 2) {
            remember(context, "FJoy / Magicut via Face Over Auto");
            return FJoyApiClient.execute(context, job, workingDirectory, cancellation, monitor);
        }

        remember(context, "AIFaceSwap / High Quality (unknown-route fallback)");
        return AIFaceSwapApiClient.execute(context, job, workingDirectory, cancellation, monitor);
    }

    private static void remember(Context context, String label) {
        AppSettings.prefs(context).edit().putString("last_engine", label).apply();
    }
}
