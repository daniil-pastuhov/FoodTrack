package com.foodtracker.android.recipe.paid

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.foodtracker.recipe.AccountKind
import com.foodtracker.recipe.AccountState
import com.foodtracker.recipe.MergeReply
import com.foodtracker.recipe.PendingMerge
import com.foodtracker.recipe.RecipeAccount
import com.foodtracker.recipe.ServerError
import com.foodtracker.recipe.ServerResult
import com.foodtracker.recipe.SignInProvider
import com.foodtracker.recipe.SignInResult
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthCredential
import com.google.firebase.auth.OAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

private const val GOOGLE_PROVIDER = "google.com"
private const val APPLE_PROVIDER = "apple.com"

/** deleteAccount wants a sign-in in the last 5 minutes; a little less leaves room for the call. */
private const val FRESH_SIGN_IN_MILLIS = 4 * 60_000L

/**
 * Firebase Auth for the paid route: an anonymous user made only when a purchase starts, Google
 * through Credential Manager, and Apple through Firebase's own web flow
 * (`OAuthProvider("apple.com")`), since Android has no native Sign in with Apple.
 *
 * Signing in on a phone that holds an anonymous user links the sign-in to it, so the uid and the
 * recipes stay. When the sign-in already has its own account, the two can't be linked: the
 * account is signed in on a second Firebase app ([RecipeFirebase.merge]) to read its count while
 * this install's user stays where it is, and only if the person agrees are the recipes moved
 * over with `mergeBalance` and the phone switched to the account.
 */
class FirebaseRecipeAccount(
    private val context: Context,
    private val firebase: RecipeFirebase
) : RecipeAccount {

    private val auth: FirebaseAuth by lazy {
        firebase.auth().also { auth -> auth.addAuthStateListener { _state.value = stateOf(it.currentUser) } }
    }

    private val _state = MutableStateFlow(AccountState())
    override val state: StateFlow<AccountState>
        get() {
            // Reading the signed-in user is local: nothing is sent until a call is made.
            _state.value = stateOf(auth.currentUser)
            return _state.asStateFlow()
        }

    /** The OAuth web client id the google-services plugin writes from google-services.json,
     *  once Google sign-in is enabled for the project. Looked up by name, since a build from a
     *  google-services.json without it has no such resource. */
    private val webClientId: String? by lazy {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        if (id == 0) null else context.getString(id).takeIf { it.isNotBlank() }
    }

    override val providers: Set<SignInProvider>
        get() = buildSet {
            add(SignInProvider.APPLE)
            if (webClientId != null) add(SignInProvider.GOOGLE)
        }

    private fun stateOf(user: FirebaseUser?): AccountState = when {
        user == null -> AccountState()
        user.isAnonymous -> AccountState(AccountKind.ANONYMOUS, purchaseKey = purchaseAccountId(user.uid))
        else -> AccountState(
            purchaseKey = purchaseAccountId(user.uid),
            kind = AccountKind.SIGNED_IN,
            providers = user.providerData.mapNotNullTo(mutableSetOf()) {
                when (it.providerId) {
                    GOOGLE_PROVIDER -> SignInProvider.GOOGLE
                    APPLE_PROVIDER -> SignInProvider.APPLE
                    else -> null
                }
            },
            email = user.email ?: user.providerData.firstNotNullOfOrNull { it.email?.takeIf(String::isNotBlank) }
        )
    }

    private fun publish() {
        _state.value = stateOf(auth.currentUser)
    }

    override suspend fun ensureUser(): Boolean {
        if (auth.currentUser != null) return true
        return try {
            auth.signInAnonymously().awaitResult()
            publish()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun signIn(provider: SignInProvider): SignInResult {
        val activity = CurrentActivity.activity ?: return SignInResult.Failed
        return try {
            when (provider) {
                SignInProvider.GOOGLE -> signInWithGoogle(activity)
                SignInProvider.APPLE -> signInWithApple(activity)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            SignInResult.Cancelled
        } catch (e: FirebaseAuthException) {
            if (e.errorCode == "ERROR_WEB_CONTEXT_CANCELED") SignInResult.Cancelled else SignInResult.Failed
        } catch (e: Exception) {
            SignInResult.Failed
        } finally {
            publish()
        }
    }

    private suspend fun googleCredential(activity: Activity): AuthCredential? {
        val clientId = webClientId ?: return null
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) return null
        return GoogleAuthProvider.getCredential(GoogleIdTokenCredential.createFrom(credential.data).idToken, null)
    }

    private suspend fun signInWithGoogle(activity: Activity): SignInResult {
        val credential = googleCredential(activity) ?: return SignInResult.Failed
        val user = auth.currentUser ?: run {
            auth.signInWithCredential(credential).awaitResult()
            dropNameAndPhoto(auth.currentUser)
            return SignInResult.SignedIn
        }
        return try {
            user.linkWithCredential(credential).awaitResult()
            dropNameAndPhoto(auth.currentUser)
            SignInResult.SignedIn
        } catch (e: FirebaseAuthUserCollisionException) {
            if (user.isAnonymous) prepareMerge(user, e.updatedCredential ?: credential) else SignInResult.InUse
        }
    }

    private fun appleProvider(): OAuthProvider = OAuthProvider.newBuilder(APPLE_PROVIDER)
        .setScopes(listOf("email"))
        .addCustomParameter("locale", Locale.getDefault().toLanguageTag())
        .build()

    private suspend fun signInWithApple(activity: Activity): SignInResult {
        // Apple's page runs in a Custom Tab, and Android can reclaim the activity meanwhile;
        // a result that arrived that way is picked up here.
        auth.pendingAuthResult?.let { pending ->
            pending.awaitResult()
            dropNameAndPhoto(auth.currentUser)
            return SignInResult.SignedIn
        }
        val user = auth.currentUser ?: run {
            auth.startActivityForSignInWithProvider(activity, appleProvider()).awaitResult()
            dropNameAndPhoto(auth.currentUser)
            return SignInResult.SignedIn
        }
        return try {
            user.startActivityForLinkWithProvider(activity, appleProvider()).awaitResult()
            dropNameAndPhoto(auth.currentUser)
            SignInResult.SignedIn
        } catch (e: FirebaseAuthUserCollisionException) {
            val credential = e.updatedCredential ?: return SignInResult.Failed
            if (user.isAnonymous) prepareMerge(user, credential) else SignInResult.InUse
        }
    }

    private class FirebaseMerge(
        val source: FirebaseUser,
        val sourceIdToken: String,
        val mergeAuth: FirebaseAuth,
        override val accountBalance: Int?
    ) : PendingMerge

    /** Signs in to the existing account on the second app and reads its count; this install's
     *  anonymous user stays signed in until the person agrees. */
    private suspend fun prepareMerge(anonymous: FirebaseUser, credential: AuthCredential): SignInResult {
        // Fetched now, while the anonymous user is still this install's: mergeBalance needs it.
        val sourceToken = anonymous.getIdToken(true).awaitResult().token ?: return SignInResult.Failed
        val mergeApp = firebase.merge
        val mergeAuth = firebase.auth(mergeApp)
        mergeAuth.signInWithCredential(credential).awaitResult()
        dropNameAndPhoto(mergeAuth.currentUser)
        val balance = (callBalance(firebase.functions(mergeApp)) as? ServerResult.Ok)?.value?.balance
        return SignInResult.NeedsMerge(FirebaseMerge(anonymous, sourceToken, mergeAuth, balance))
    }

    override suspend fun completeMerge(merge: PendingMerge): ServerResult<MergeReply> {
        val pending = merge as? FirebaseMerge ?: return ServerResult.Err(ServerError.MERGE_NOT_ALLOWED)
        val accountUser = pending.mergeAuth.currentUser ?: return ServerResult.Err(ServerError.MERGE_NOT_ALLOWED)
        // The anonymous user's ID token lasts an hour, and the question may have stood a while,
        // or this be a retry: a current one, as long as that user is still this install's.
        val sourceToken = if (auth.currentUser?.uid == pending.source.uid) {
            runCatching { pending.source.getIdToken(false).awaitResult().token }.getOrNull() ?: pending.sourceIdToken
        } else {
            pending.sourceIdToken
        }
        val result = callRaw(
            firebase.functions(firebase.merge),
            "mergeBalance",
            JsonObject(mapOf("sourceIdToken" to JsonPrimitive(sourceToken))),
            60L
        )
        return when (result) {
            is ServerResult.Ok -> {
                // Merged: the anonymous user is deleted on the server, so the install switches
                // to the account whatever the answer says.
                switchTo(accountUser, pending)
                // A reply without a readable count is a server bug; the count is asked for then.
                val reply = mergeReplyOf(result.value)
                    ?: MergeReply(balance = (callBalance(firebase.functions()) as? ServerResult.Ok)?.value?.balance ?: 0)
                ServerResult.Ok(reply)
            }
            // Nothing changed; the merge stays open to be asked again or cancelled.
            is ServerResult.Err -> result
        }
    }

    private suspend fun switchTo(accountUser: FirebaseUser, pending: FirebaseMerge) {
        try {
            auth.updateCurrentUser(accountUser).awaitResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The recipes have moved; signing in again reaches them.
            auth.signOut()
        }
        pending.mergeAuth.signOut()
        publish()
    }

    override suspend fun cancelMerge(merge: PendingMerge) {
        (merge as? FirebaseMerge)?.mergeAuth?.signOut()
    }

    override fun needsFreshSignIn(): Boolean {
        val user = auth.currentUser ?: return false
        if (user.isAnonymous) return false
        // Apple's token is revoked from the re-authentication's result, so an Apple-linked
        // account always signs in again before it is deleted, however recent the last sign-in.
        if (user.providerData.any { it.providerId == APPLE_PROVIDER }) return true
        val last = user.metadata?.lastSignInTimestamp ?: return true
        return System.currentTimeMillis() - last > FRESH_SIGN_IN_MILLIS
    }

    override suspend fun reauthenticateForDeletion(): Boolean {
        val user = auth.currentUser ?: return false
        val activity = CurrentActivity.activity ?: return false
        val providerIds = user.providerData.map { it.providerId }
        return try {
            if (APPLE_PROVIDER in providerIds) {
                val result: AuthResult = user.startActivityForReauthenticateWithProvider(activity, appleProvider()).awaitResult()
                dropNameAndPhoto(auth.currentUser)
                // Apple asks apps to revoke its tokens when an account is deleted.
                (result.credential as? OAuthCredential)?.accessToken?.let { token ->
                    runCatching { auth.revokeAccessToken(token).awaitResult() }
                }
                true
            } else if (GOOGLE_PROVIDER in providerIds) {
                val credential = googleCredential(activity) ?: return false
                user.reauthenticate(credential).awaitResult()
                dropNameAndPhoto(auth.currentUser)
                true
            } else {
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun signOut() {
        auth.signOut()
        publish()
        // So the next Google sign-in asks which account rather than reusing this one.
        val activity = CurrentActivity.activity ?: return
        runCatching { CredentialManager.create(activity).clearCredentialState(ClearCredentialStateRequest()) }
    }
}

/**
 * The app keeps no names: Google's ID token, and Apple's on a first sign-in, give Firebase the
 * person's name (and Google a photo), which Firebase copies onto the user. Cleared straight after
 * every sign-in, link and re-authentication, before anything else uses the user. The copy in the
 * user's provider data can't be cleared from the client.
 */
private suspend fun dropNameAndPhoto(user: FirebaseUser?) {
    if (user != null) dropNameAndPhoto(FirebaseUserProfile(user))
}

/** The part of a signed-in user that holds a name and a photo. */
internal interface NamedProfile {
    val hasNameOrPhoto: Boolean
    suspend fun clearNameAndPhoto()
}

/** Clears [profile]'s name and photo when it has either. A failure leaves sign-in as it is and
 *  is tried again at the next sign-in; nothing about the person is logged. True when cleared. */
internal suspend fun dropNameAndPhoto(profile: NamedProfile): Boolean {
    if (!profile.hasNameOrPhoto) return true
    return try {
        profile.clearNameAndPhoto()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}

private class FirebaseUserProfile(private val user: FirebaseUser) : NamedProfile {
    override val hasNameOrPhoto: Boolean get() = user.displayName != null || user.photoUrl != null

    override suspend fun clearNameAndPhoto() {
        // Null removes the field (UserProfileChangeRequest).
        user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(null).setPhotoUri(null).build()).awaitResult()
    }
}
