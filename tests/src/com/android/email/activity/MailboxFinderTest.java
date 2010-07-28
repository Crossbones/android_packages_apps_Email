/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.email.activity;

import com.android.email.Controller;
import com.android.email.Email;
import com.android.email.TestUtils;
import com.android.email.mail.MessagingException;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailProvider;
import com.android.email.provider.ProviderTestUtils;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.test.InstrumentationTestCase;
import android.test.IsolatedContext;
import android.test.ProviderTestCase2;
import android.test.RenamingDelegatingContext;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;
import android.test.suitebuilder.annotation.LargeTest;

import java.io.File;

/**
 * Test case for {@link MailboxFinder}.
 *
 * We need to use {@link InstrumentationTestCase} so that we can create AsyncTasks on the UI thread
 * using {@link InstrumentationTestCase#runTestOnUiThread}.  This class also needs an isolated
 * context, which is provided by {@link ProviderTestCase2}.  We can't derive from two classes,
 * so we just copy the code for an isolate context to here.
 */
@LargeTest
public class MailboxFinderTest extends InstrumentationTestCase {
    private static final int TIMEOUT = 10; // in seconds

    // These are needed to run the provider in a separate context
    private IsolatedContext mProviderContext;
    private MockContentResolver mResolver;
    private EmailProvider mProvider;

    // Test target
    private MailboxFinder mMailboxFinder;

    // Mock to track callback invocations.
    private MockController mMockController;
    private MockCallback mCallback;

    private Context getContext() {
        return getInstrumentation().getTargetContext();
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        setUpProviderContext();

        mCallback = new MockCallback();
        mMockController = new MockController(getContext());
        Controller.injectMockControllerForTest(mMockController);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        Controller.injectMockControllerForTest(null);
    }

    /** Copied from ProviderTestCase2 and modified a bit. */
    private class MockContext2 extends MockContext {
        @Override
        public Resources getResources() {
            return getContext().getResources();
        }

        @Override
        public File getDir(String name, int mode) {
            return getContext().getDir("mockcontext2_" + name, mode);
        }
    }

    /** {@link IsolatedContext} + getApplicationContext() */
    private static class MyIsolatedContext extends IsolatedContext {
        public MyIsolatedContext(ContentResolver resolver, Context targetContext) {
            super(resolver, targetContext);
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }
    }

    /** Copied from ProviderTestCase2 and modified a bit. */
    private void setUpProviderContext() {
        mResolver = new MockContentResolver();
        final String filenamePrefix = "test.";
        RenamingDelegatingContext targetContextWrapper = new
                RenamingDelegatingContext(
                new MockContext2(), // The context that most methods are
                                    //delegated to
                getContext(), // The context that file methods are delegated to
                filenamePrefix);
        mProviderContext = new MyIsolatedContext(mResolver, targetContextWrapper);
        mProviderContext.getContentResolver();

        mProvider = new EmailProvider();
        mProvider.attachInfo(mProviderContext, null);
        assertNotNull(mProvider);
        mResolver.addProvider(EmailContent.AUTHORITY, mProvider);
    }

    /**
     * Create an account and returns the ID.
     */
    private long createAccount(boolean securityHold) {
        Account acct = ProviderTestUtils.setupAccount("acct1", false, mProviderContext);
        if (securityHold) {
            acct.mFlags |= Account.FLAGS_SECURITY_HOLD;
        }
        acct.save(mProviderContext);
        return acct.mId;
    }

    /**
     * Create a mailbox and return the ID.
     */
    private long createMailbox(long accountId, int mailboxType) {
        EmailContent.Mailbox box = new EmailContent.Mailbox();
        box.mDisplayName = "mailbox";
        box.mAccountKey = accountId;
        box.mType = mailboxType;
        box.mFlagVisible = true;
        box.mVisibleLimit = Email.VISIBLE_LIMIT_DEFAULT;
        box.save(mProviderContext);
        return box.mId;
    }

    /**
     * Create a {@link MailboxFinder} and kick it.
     */
    private void createAndStartFinder(final long accountId, final int mailboxType)
            throws Throwable {
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                mMailboxFinder = new MailboxFinder(mProviderContext, accountId, mailboxType,
                        mCallback);
                mMailboxFinder.startLookup();
            }
        });
    }

    /**
     * Wait until any of the {@link MailboxFinder.Callback} method or
     * {@link Controller#updateMailboxList} is called.
     */
    private void waitUntilCallbackCalled() {
        TestUtils.waitUntil("", new TestUtils.Condition() {
            @Override
            public boolean isMet() {
                return mCallback.isAnyMethodCalled() || mMockController.mCalledUpdateMailboxList;
            }
        }, TIMEOUT);
    }

    /**
     * Test: Account is on security hold.
     */
    public void testSecurityHold() throws Throwable {
        final long accountId = createAccount(true);

        createAndStartFinder(accountId, Mailbox.TYPE_INBOX);
        waitUntilCallbackCalled();

        assertFalse(mCallback.mCalledAccountNotFound);
        assertTrue(mCallback.mCalledAccountSecurityHold);
        assertFalse(mCallback.mCalledMailboxFound);
        assertFalse(mCallback.mCalledMailboxNotFound);
        assertFalse(mMockController.mCalledUpdateMailboxList);
    }

    /**
     * Test: Account does not exist.
     */
    public void testAccountNotFound() throws Throwable {
        createAndStartFinder(123456, Mailbox.TYPE_INBOX); // No such account.
        waitUntilCallbackCalled();

        assertTrue(mCallback.mCalledAccountNotFound);
        assertFalse(mCallback.mCalledAccountSecurityHold);
        assertFalse(mCallback.mCalledMailboxFound);
        assertFalse(mCallback.mCalledMailboxNotFound);
        assertFalse(mMockController.mCalledUpdateMailboxList);
    }

    /**
     * Test: Mailbox found
     */
    public void testMailboxFound() throws Throwable {
        final long accountId = createAccount(false);
        final long mailboxId = createMailbox(accountId, Mailbox.TYPE_INBOX);

        createAndStartFinder(accountId, Mailbox.TYPE_INBOX);
        waitUntilCallbackCalled();

        assertFalse(mCallback.mCalledAccountNotFound);
        assertFalse(mCallback.mCalledAccountSecurityHold);
        assertTrue(mCallback.mCalledMailboxFound);
        assertFalse(mCallback.mCalledMailboxNotFound);
        assertFalse(mMockController.mCalledUpdateMailboxList);

        assertEquals(accountId, mCallback.mAccountId);
        assertEquals(mailboxId, mCallback.mMailboxId);
    }

    /**
     * Test: Account exists, but mailbox doesn't -> Get {@link Controller} to update the mailbox
     * list -> mailbox still doesn't exist.
     */
    public void testMailboxNotFound() throws Throwable {
        final long accountId = createAccount(false);

        createAndStartFinder(accountId, Mailbox.TYPE_INBOX);
        waitUntilCallbackCalled();

        // Mailbox not found, so the finder try network-looking up.
        assertFalse(mCallback.mCalledAccountNotFound);
        assertFalse(mCallback.mCalledAccountSecurityHold);
        assertFalse(mCallback.mCalledMailboxFound);
        assertFalse(mCallback.mCalledMailboxNotFound);

        // Controller.updateMailboxList() should have been called, with the account id.
        assertTrue(mMockController.mCalledUpdateMailboxList);
        assertEquals(accountId, mMockController.mPassedAccountId);

        mMockController.reset();

        // Imitate the mCallback...
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                mMailboxFinder.getControllerResultsForTest().updateMailboxListCallback(
                        null, accountId, 100);
            }
        });

        // Task should have started, so wait for the response...
        waitUntilCallbackCalled();

        assertFalse(mCallback.mCalledAccountNotFound);
        assertFalse(mCallback.mCalledAccountSecurityHold);
        assertFalse(mCallback.mCalledMailboxFound);
        assertTrue(mCallback.mCalledMailboxNotFound);
        assertFalse(mMockController.mCalledUpdateMailboxList);
    }

    /**
     * Test: Account exists, but mailbox doesn't -> Get {@link Controller} to update the mailbox
     * list -> found mailbox this time.
     */
    public void testMailboxFoundOnNetwork() throws Throwable {
        final long accountId = createAccount(false);

        createAndStartFinder(accountId, Mailbox.TYPE_INBOX);
        waitUntilCallbackCalled();

        // Mailbox not found, so the finder try network-looking up.
        assertFalse(mCallback.mCalledAccountNotFound);
        assertFalse(mCallback.mCalledAccountSecurityHold);
        assertFalse(mCallback.mCalledMailboxFound);
        assertFalse(mCallback.mCalledMailboxNotFound);

        // Controller.updateMailboxList() should have been called, with the account id.
        assertTrue(mMockController.mCalledUpdateMailboxList);
        assertEquals(accountId, mMockController.mPassedAccountId);

        mMockController.reset();

        // Create mailbox at this point.
        final long mailboxId = createMailbox(accountId, Mailbox.TYPE_INBOX);

        // Imitate the mCallback...
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                mMailboxFinder.getControllerResultsForTest().updateMailboxListCallback(
                        null, accountId, 100);
            }
        });

        // Task should have started, so wait for the response...
        waitUntilCallbackCalled();

        assertFalse(mCallback.mCalledAccountNotFound);
        assertFalse(mCallback.mCalledAccountSecurityHold);
        assertTrue(mCallback.mCalledMailboxFound);
        assertFalse(mCallback.mCalledMailboxNotFound);
        assertFalse(mMockController.mCalledUpdateMailboxList);

        assertEquals(accountId, mCallback.mAccountId);
        assertEquals(mailboxId, mCallback.mMailboxId);
    }

    /**
     * Test: Account exists, but mailbox doesn't -> Get {@link Controller} to update the mailbox
     * list -> network error.
     */
    public void testMailboxNotFoundNetworkError() throws Throwable {
        final long accountId = createAccount(false);

        createAndStartFinder(accountId, Mailbox.TYPE_INBOX);
        waitUntilCallbackCalled();

        // Mailbox not found, so the finder try network-looking up.
        assertFalse(mCallback.mCalledAccountNotFound);
        assertFalse(mCallback.mCalledAccountSecurityHold);
        assertFalse(mCallback.mCalledMailboxFound);
        assertFalse(mCallback.mCalledMailboxNotFound);

        // Controller.updateMailboxList() should have been called, with the account id.
        assertTrue(mMockController.mCalledUpdateMailboxList);
        assertEquals(accountId, mMockController.mPassedAccountId);

        mMockController.reset();

        // Imitate the mCallback...
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                // network error.
                mMailboxFinder.getControllerResultsForTest().updateMailboxListCallback(
                        new MessagingException("Network error"), accountId, 0);
            }
        });

        assertFalse(mCallback.mCalledAccountNotFound);
        assertFalse(mCallback.mCalledAccountSecurityHold);
        assertFalse(mCallback.mCalledMailboxFound);
        assertTrue(mCallback.mCalledMailboxNotFound);
        assertFalse(mMockController.mCalledUpdateMailboxList);
    }

    /**
     * Test: Mailbox not found (mailbox of different type exists)
     */
    public void testMailboxNotFound2() throws Throwable {
        final long accountId = createAccount(false);
        final long mailboxId = createMailbox(accountId, Mailbox.TYPE_DRAFTS);

        createAndStartFinder(accountId, Mailbox.TYPE_INBOX);
        waitUntilCallbackCalled();

        assertFalse(mCallback.mCalledAccountNotFound);
        assertFalse(mCallback.mCalledAccountSecurityHold);
        assertFalse(mCallback.mCalledMailboxFound);
        assertFalse(mCallback.mCalledMailboxNotFound);
        assertTrue(mMockController.mCalledUpdateMailboxList);
    }

    /**
     * A {@link Controller} that remembers if updateMailboxList has been called.
     */
    private static class MockController extends Controller {
        public long mPassedAccountId;
        public boolean mCalledUpdateMailboxList;

        public void reset() {
            mPassedAccountId = -1;
            mCalledUpdateMailboxList = false;
        }

        protected MockController(Context context) {
            super(context);
        }

        @Override
        public void updateMailboxList(long accountId) {
            mCalledUpdateMailboxList = true;
            mPassedAccountId = accountId;
        }
    }

    /**
     * Callback that logs what method is called with what arguments.
     */
    private static class MockCallback implements MailboxFinder.Callback {
        public boolean mCalledAccountNotFound;
        public boolean mCalledAccountSecurityHold;
        public boolean mCalledMailboxFound;
        public boolean mCalledMailboxNotFound;

        public long mAccountId = -1;
        public long mMailboxId = -1;

        public boolean isAnyMethodCalled() {
            return mCalledAccountNotFound || mCalledAccountSecurityHold || mCalledMailboxFound
                    || mCalledMailboxNotFound;
        }

        @Override
        public void onAccountNotFound() {
            mCalledAccountNotFound = true;
        }

        @Override
        public void onAccountSecurityHold(long accountId) {
            mCalledAccountSecurityHold = true;
            mAccountId = accountId;
        }

        @Override
        public void onMailboxFound(long accountId, long mailboxId) {
            mCalledMailboxFound = true;
            mAccountId = accountId;
            mMailboxId = mailboxId;
        }

        @Override
        public void onMailboxNotFound(long accountId) {
            mCalledMailboxNotFound = true;
            mAccountId = accountId;
        }
    }
}
