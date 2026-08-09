package com.walter.spring.ai.ops.code

enum class PullRequestAction {
    OPENED,
    SYNCHRONIZE,
    IGNORED;

    companion object {
        fun fromGithub(action: String): PullRequestAction = when (action) {
            "opened", "reopened", "ready_for_review" -> OPENED
            "synchronize" -> SYNCHRONIZE
            else -> IGNORED
        }

        fun fromGitlab(action: String, hasOldrev: Boolean): PullRequestAction = when (action) {
            "open", "reopen" -> OPENED
            "update" -> if (hasOldrev) SYNCHRONIZE else IGNORED
            else -> IGNORED
        }
    }
}