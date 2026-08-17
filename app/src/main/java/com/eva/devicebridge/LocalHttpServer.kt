package com.eva.devicebridge

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream

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
                "/launch_app" -> handleLaunchApp(session)
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

    private fun handleLaunchApp(session: IHTTPSession): Response {
        val body = readJsonBody(session)
        val packageName = body.getString("package")
        val launchIntent = service.packageManager.getLaunchIntentForPackage(packageName)
            ?: return newFixedLengthResponse(
                Response.Status.NOT_FOUND, "text/plain",
                "no launchable activity found for package: $packageName"
            )
        launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = try {
            service.startActivity(launchIntent)
            true
        } catch (e: Exception) {
            false
        }
        return actionResult(ok)
    }

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
