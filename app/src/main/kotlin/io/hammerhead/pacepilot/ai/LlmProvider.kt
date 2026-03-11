package io.hammerhead.pacepilot.ai

enum class LlmProvider(val displayName: String) {
    GEMINI("Gemini 2.0 Flash (recommended)"),
    MERCURY("Mercury-2 (experimental, fastest)"),
    DISABLED("Disabled — rules only"),
}
