package com.danielealbano.androidremotecontrolmcp.data.model

/** Optional Android permissions that gate MCP tools (whole category) or a single tool parameter (Plan 56). */
enum class OptionalToolPermission {
    CAMERA,
    LOCATION,
    NOTIFICATION_LISTENER,
    MICROPHONE,
}

/** Single source of truth mapping each optional permission to the tools (or params) it gates. */
object OptionalToolPermissions {
    /** Whole-category (tool-level) gates. MICROPHONE intentionally absent — it gates a param only. */
    val TOOLS_BY_PERMISSION: Map<OptionalToolPermission, Set<String>> =
        mapOf(
            OptionalToolPermission.CAMERA to
                setOf(
                    "list_cameras",
                    "list_camera_photo_resolutions",
                    "list_camera_video_resolutions",
                    "take_camera_photo",
                    "save_camera_photo",
                    "save_camera_video",
                ),
            OptionalToolPermission.LOCATION to setOf("get_location"),
            OptionalToolPermission.NOTIFICATION_LISTENER to
                setOf(
                    "notification_list",
                    "notification_open",
                    "notification_dismiss",
                    "notification_snooze",
                    "notification_action",
                    "notification_reply",
                ),
        )

    /** Param-level gates: permission -> (toolName -> paramNames). */
    val PARAMS_BY_PERMISSION: Map<OptionalToolPermission, Map<String, Set<String>>> =
        mapOf(
            OptionalToolPermission.MICROPHONE to mapOf("save_camera_video" to setOf("audio")),
        )

    /** Tool names whose gating permission is NOT in [granted]. */
    fun toolsMissingPermission(granted: Set<OptionalToolPermission>): Set<String> =
        TOOLS_BY_PERMISSION
            .filterKeys { it !in granted }
            .values
            .flatten()
            .toSet()

    /** Params (toolName -> paramNames) whose gating permission is NOT in [granted]. */
    fun paramsMissingPermission(granted: Set<OptionalToolPermission>): Map<String, Set<String>> {
        val result = mutableMapOf<String, MutableSet<String>>()
        PARAMS_BY_PERMISSION.filterKeys { it !in granted }.values.forEach { toolParams ->
            toolParams.forEach { (tool, params) -> result.getOrPut(tool) { mutableSetOf() }.addAll(params) }
        }
        return result.mapValues { it.value.toSet() }
    }

    /** Reverse index: tool name -> gating permission. */
    private val TOOL_TO_PERMISSION: Map<String, OptionalToolPermission> =
        TOOLS_BY_PERMISSION.entries.flatMap { (perm, tools) -> tools.map { it to perm } }.toMap()

    /** Reverse index: (tool name, param name) -> gating permission. */
    private val PARAM_TO_PERMISSION: Map<Pair<String, String>, OptionalToolPermission> =
        PARAMS_BY_PERMISSION.entries
            .flatMap { (perm, tools) ->
                tools.entries.flatMap { (tool, params) -> params.map { (tool to it) to perm } }
            }.toMap()

    /** The optional permission gating [toolName], or null if the tool is not permission-gated. */
    fun permissionForTool(toolName: String): OptionalToolPermission? = TOOL_TO_PERMISSION[toolName]

    /** The optional permission gating [paramName] of [toolName], or null. */
    fun permissionForParam(
        toolName: String,
        paramName: String,
    ): OptionalToolPermission? = PARAM_TO_PERMISSION[toolName to paramName]

    /** Builds the granted-permission set from individual permission booleans (pure; unit-testable). */
    fun grantedPermissions(
        camera: Boolean,
        microphone: Boolean,
        location: Boolean,
        notificationListener: Boolean,
    ): Set<OptionalToolPermission> =
        buildSet {
            if (camera) add(OptionalToolPermission.CAMERA)
            if (microphone) add(OptionalToolPermission.MICROPHONE)
            if (location) add(OptionalToolPermission.LOCATION)
            if (notificationListener) add(OptionalToolPermission.NOTIFICATION_LISTENER)
        }

    /**
     * Effective config for registration: stored denylist plus the tools/params whose optional
     * permission is not in [granted]. The stored [stored] instance is NOT mutated.
     */
    fun effectivePermissions(
        stored: ToolPermissionsConfig,
        granted: Set<OptionalToolPermission>,
    ): ToolPermissionsConfig {
        val missingParams = paramsMissingPermission(granted)
        val mergedParams =
            (stored.disabledParams.keys + missingParams.keys).associateWith { tool ->
                stored.disabledParams[tool].orEmpty() + missingParams[tool].orEmpty()
            }
        return stored.copy(
            disabledTools = stored.disabledTools + toolsMissingPermission(granted),
            disabledParams = mergedParams,
        )
    }
}
