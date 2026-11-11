package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D60, OD46: the wizard's Google sign-in button must be able to clear its own status. The status
 * reads the google_account pref, and the Google Voice screen does not write it, so with no account
 * saved the button opens the account chooser and the result is saved to google_account before the
 * Google Voice screen opens.
 */
public class WizardSignInSavesAccountSourceTest {

    private static String setupActivity() throws IOException {
        Path p = Paths.get(System.getProperty("user.dir"), "src", "main");
        if (!Files.isDirectory(p)) p = Paths.get(System.getProperty("user.dir"), "app", "src", "main");
        return new String(Files.readAllBytes(
                p.resolve("java/com/voice2sms/SetupActivity.java")), StandardCharsets.UTF_8);
    }

    @Test
    public void signInButtonStartsTheAccountChooserForAResult() throws IOException {
        String s = setupActivity();
        assertTrue("sign-in must start SetupChecks.fixIntent(GOOGLE_ACCOUNT) (the account chooser)",
                s.contains("SetupChecks.fixIntent(this, SetupChecks.Item.GOOGLE_ACCOUNT)"));
        assertTrue("the chooser must be started for a result",
                s.contains("startActivityForResult(chooser, REQUEST_GOOGLE_ACCOUNT)"));
    }

    @Test
    public void chosenAccountIsSavedToGoogleAccountThenGoogleVoiceOpens() throws IOException {
        String s = setupActivity();
        Matcher m = Pattern.compile("requestCode == REQUEST_GOOGLE_ACCOUNT\\)\\s*\\{(.*?)\\n        \\}",
                Pattern.DOTALL).matcher(s);
        assertTrue("onActivityResult must handle REQUEST_GOOGLE_ACCOUNT", m.find());
        String body = m.group(1);
        assertTrue("must check RESULT_OK", body.contains("RESULT_OK"));
        assertTrue("must read AccountManager.KEY_ACCOUNT_NAME",
                body.contains("AccountManager.KEY_ACCOUNT_NAME"));
        int save = body.indexOf("putString(\"google_account\"");
        int open = body.indexOf("openGoogleVoice()");
        assertTrue("must save the chosen account to google_account", save >= 0);
        assertTrue("must open the Google Voice screen after saving", open > save);
    }
}
