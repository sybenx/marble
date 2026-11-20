package cc.aaro.cosign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountPasswordTest {
    @Test fun aGoodUsernameAndMatchingPasswordsPass() {
        assertNull(AccountPassword.problem("Aaron ", "long enough pw", "long enough pw"))
    }

    @Test fun mismatchShortAndBadUsernameAreRefusedInPlainWords() {
        assertEquals("The two passwords do not match.", AccountPassword.problem("aaron", "long enough pw", "long enough pX"))
        assertEquals("Use a password of at least 10 characters.", AccountPassword.problem("aaron", "short", "short"))
        assertEquals("A username is 3 to 32 of a-z, 0-9, dot, dash and underscore.", AccountPassword.problem("a b", "long enough pw", "long enough pw"))
        assertTrue(AccountPassword.problem("ab", "long enough pw", "long enough pw") != null)
    }

    @Test fun setupNeedsAPasswordToCreateOrImportButNotToJoin() {
        assertEquals("Choose a username and a password first.", AccountPassword.setupProblem(true, null))
        assertEquals("Choose a username and a password first.", AccountPassword.setupProblem(true, "no newline"))
        assertTrue(AccountPassword.setupProblem(true, AccountPassword.pack("aaron", "short")) != null)
        assertNull(AccountPassword.setupProblem(true, AccountPassword.pack("Aaron", "long enough pw")))
        assertNull(AccountPassword.setupProblem(false, null))
    }

    @Test fun packAndUnpackKeepNewlinesInThePassword() {
        assertEquals("aaron" to "a\nb with more", AccountPassword.unpack(AccountPassword.pack(" Aaron", "a\nb with more")))
        assertNull(AccountPassword.unpack(null))
    }

    @Test fun thePromptShowsOnlyWhenTheBoxSaysThereIsNone() {
        assertTrue(AccountPassword.needsPrompt(true, false))
        assertFalse(AccountPassword.needsPrompt(true, true))
        assertFalse(AccountPassword.needsPrompt(true, null))      // an older box that does not say
        assertFalse(AccountPassword.needsPrompt(false, false))    // not in an account
        assertFalse(AccountPassword.PROMPT.contains('—'))
    }
}
