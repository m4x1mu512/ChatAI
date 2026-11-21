package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.core.model.GitHubPendingAction
import com.example.core.model.GitHubPendingApproval
import com.example.ui.screens.chat.GitHubApprovalBannerCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GitHubApprovalCardRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `large approval card with many actions and long previews keeps approve and reject buttons visible`() {
        val longSummary = (1..25).joinToString("\n") { "Detailed change description line $it explaining why the file must be modified." }
        val longPreview = (1..60).joinToString("\n") { "+ val line$it = \"some updated kotlin code in repository\"" }
        val hugeActions = (1..6).map { idx ->
            GitHubPendingAction(
                callId = "call_$idx",
                toolName = "github_create_or_update_file",
                title = "Update file src/module$idx/Repository$idx.kt",
                summary = longSummary,
                previewContent = longPreview,
            )
        }
        val approval = GitHubPendingApproval(
            chatId = "chat-1",
            repoFullName = "owner/repo",
            actions = hugeActions,
        )

        var approvedCount = 0
        var rejectedCount = 0

        composeTestRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                ) {
                    GitHubApprovalBannerCard(
                        approval = approval,
                        maxCardHeight = 320.dp,
                        onApprove = { approvedCount++ },
                        onReject = { rejectedCount++ },
                    )
                }
            }
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("github_approval_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("github_approval_reject").assertIsDisplayed()
        composeTestRule.onNodeWithTag("github_approval_confirm").assertIsDisplayed()

        composeTestRule.onNodeWithTag("github_approval_confirm").performClick()
        composeTestRule.onNodeWithTag("github_approval_reject").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, approvedCount)
        assertEquals(1, rejectedCount)
    }
}
