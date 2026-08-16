package com.eva.devicebridge

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * The local IPC channel between this app and the Python side running in
 * Termux.
 *
 * WHY LOCALHOST HTTP:
 *   - Termux and this app are separate processes/UIDs with no shared
 *     storage or signing key, so in-process bindings (AIDL/Binder, a bound
 *     Service with a custom Messenger) would require Termux to hold a
 *     compiled interface definition and bind via an explicit component
 *     name -- doable, but noticeably more code and more fragile across
 *     Android versions than a socket.
 *   - A shared-file/polling bridge (write a request file, watch for a
 *     response file) is simple but adds directory-watching latency and
 *     race conditions (two processes touching the same file); it's also
 *     what Termux:API-style plugins do only because they route through
 *     Android's Intent/BroadcastReceiver system, which requires a
 *     *separate* Termux:API companion app to be installed too.
 *   - A plain TCP socket on 127.0.0.1 needs nothing else installed, is
 *     reachable from Termux with zero special permissions (Termux, like
 *     any app, can open outbound sockets to localhost), has the lowest
 *     latency of the three options, and -- critically -- loopback sockets
 *     are NOT sandboxed per-app on stock, non-rooted Android. Any app can
 *     connect to 127.0.0.1:<port> that any other app is listening on.
 *   - HTTP (via NanoHTTPD) on top of that raw socket buys us: painless
 *     binary transfer for screenshots (just write bytes to the response
 *     stream), trivial JSON request/response bodies via any Python
 *     `requests` call, and free debuggability -- `curl` or a browser can
 *     hit these endpoints directly while developing.
 *
 * SECURITY NOTE: the server is bound explicitly to "127.0.0.1", not
 * "0.0.0.0", so it is unreachable from the network/Wi-Fi interface --
 * only processes on the same device can talk to it.
 */
class LocalHttpServer(private val service: EvaAccessibilityService) :
    NanoHTTPD("127.0.0.1", PORT) {

    companion object {
        const val PORT = 8765
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.uri) {
                "/health" -> jsonOk(JSONObject().put("status", "ok"))
                "/ui_dump" -> handleUiDump()
                "/current_app" -> handleCurrentApp()
                "/screenshot" -> handleScreenshot()
                "/tap" -> handleTap(session)
                "/swipe" -> handleSwipe(session)
                "/long_press" -> handleLongPress(session)
                "/type" -> handleType(session)
                "/key" -> handleKey(session)
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND, "text/plain", "no such endpoint: ${session.uri}"
                )
            }
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", "error: ${e.message}"
            )
        }
    }

    // ---- read-only endpoints -------------------------------------------

    private fun handleUiDump(): Response {
        val root = service.rootInActiveWindow
            ?: return serviceUnavailable("no active window (screen may be locked)")
        val xml = try {
            UiTreeDumper.dump(root)
        } finally {
            root.recycle()
        }
        return newFixedLengthResponse(Response.Status.OK, "text/xml", xml)
    }

    private fun handleCurrentApp(): Response {
        val json = JSONObject()
            .put("package", service.lastPackageName)
            .put("activity", service.lastClassName)
        return jsonOk(json)
    }

    private fun handleScreenshot(): Response {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            return newFixedLengthResponse(
                Response.Status.NOT_IMPLEMENTED, "text/plain",
                "Screenshots require Android 11 (API 30) or higher."
            )
        }
        val png = ScreenshotHelper.capturePng(service)
            ?: return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", "screenshot capture failed or timed out"
            )
        return newFixedLengthResponse(
            Response.Status.OK, "image/png", ByteArrayInputStream(png), png.size.toLong()
        )
    }

    // ---- action endpoints (POST, JSON body) ------------------------------

    private fun handleTap(session: IHTTPSession): Response {
        val body = readJsonBody(session)
        val x = body.getInt("x")
        val y = body.getInt("y")
        val ok = GestureController.tap(service, x, y)
        return actionResult(ok)
    }

    private fun handleSwipe(session: IHTTPSession): Response {
        val body = readJsonBody(session)
        val ok = GestureController.swipe(
            service,
            body.getInt("x1"), body.getInt("y1"),
            body.getInt("x2"), body.getInt("y2"),
            body.optLong("duration_ms", 300L)
        )
        return actionResult(ok)
    }

    private fun handleLongPress(session: IHTTPSession): Response {
        val body = readJsonBody(session)
        val ok = GestureController.longPress(
            service, body.getInt("x"), body.getInt("y"), body.optLong("duration_ms", 800L)
        )
        return actionResult(ok)
    }

    private fun handleType(session: IHTTPSession): Response {
        val body = readJsonBody(session)
        val text = body.getString("text")
        val x = if (body.has("x")) body.getInt("x") else null
        val y = if (body.has("y")) body.getInt("y") else null
        val root = service.rootInActiveWindow
            ?: return serviceUnavailable("no active window (screen may be locked)")
        val ok = try {
            GestureController.typeText(root, x, y, text)
        } finally {
            root.recycle()
        }
        return actionResult(ok)
    }

    private fun handleKey(session: IHTTPSession): Response {
        val body = readJsonBody(session)
        val action = body.getString("action")
        val globalAction = when (action) {
            "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/plain", "unknown action: $action"
            )
        }
        val ok = service.performGlobalAction(globalAction)
        return actionResult(ok)
    }

    // ---- helpers ---------------------------------------------------------

    private fun readJsonBody(session: IHTTPSession): JSONObject {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val raw = map["postData"] ?: "{}"
        return JSONObject(raw)
    }

    private fun jsonOk(json: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())

    private fun actionResult(success: Boolean): Response =
        jsonOk(JSONObject().put("success", success))

    private fun serviceUnavailable(reason: String): Response =
        newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", reason)
}
