package com.watermonitor.app.ui.auth

import android.graphics.drawable.Animatable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.FacebookAuthProvider
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import android.content.Intent
import com.google.android.material.card.MaterialCardView
import com.watermonitor.app.R
import com.watermonitor.app.data.model.AuthProvider
import com.watermonitor.app.databinding.FragmentLoginBinding
import com.watermonitor.app.utils.AccountManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val bindingSafe get() = _binding
    private val callbackManager = CallbackManager.Factory.create()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return _binding!!.root
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Register Facebook callback once per fragment instance (not per view
        // recreation) to avoid duplicate registrations in CallbackManagerImpl.
        LoginManager.getInstance().registerCallback(callbackManager, object : FacebookCallback<LoginResult> {
            override fun onSuccess(result: LoginResult) {
                firebaseAuthWithFacebook(result.accessToken.token)
            }
            override fun onCancel() {
                showError("Facebook login cancelled")
            }
            override fun onError(error: FacebookException) {
                showError("Facebook login error: ${error.message}")
            }
        })
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = bindingSafe ?: return

        // Start SVG animation
        val drawable = binding.ivLoginAnim.drawable
        if (drawable is Animatable) {
            drawable.start()
        }

        // Load saved accounts
        loadSavedAccounts()

        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()

            if (email.isEmpty() || pass.isEmpty()) {
                showError("Please enter email and password")
                return@setOnClickListener
            }

            binding.btnLogin.isEnabled = false

            FirebaseAuth.getInstance().signInWithEmailAndPassword(email, pass)
                .addOnSuccessListener {
                    val currentBinding = bindingSafe ?: return@addOnSuccessListener
                    currentBinding.btnLogin.isEnabled = true

                    val user = FirebaseAuth.getInstance().currentUser
                    if (user != null && !user.isEmailVerified) {
                        FirebaseAuth.getInstance().signOut()
                        Snackbar.make(requireView(), R.string.auth_email_not_verified, Snackbar.LENGTH_LONG)
                            .setAction(R.string.auth_resend_verification) {
                                user.sendEmailVerification()
                            }.show()
                    } else if (user != null) {
                        AccountManager.saveAccount(requireContext(), user, AuthProvider.EMAIL)
                        findNavController().navigate(R.id.action_loginFragment_to_dashboardFragment)
                    }
                }
                .addOnFailureListener { e ->
                    val currentBinding = bindingSafe ?: return@addOnFailureListener
                    currentBinding.btnLogin.isEnabled = true
                    showError("Error: ${e.message}")
                }
        }

        binding.tvGoToRegister.setOnClickListener {
            findNavController().navigate(R.id.action_loginFragment_to_registerFragment)
        }

        // Google Sign In using CredentialManager
        binding.btnGoogleLogin.setOnClickListener {
            signInWithGoogle()
        }

        // Facebook Sign In using Firebase OAuthProvider
        binding.btnFacebookLogin.setOnClickListener {
            signInWithFacebook()
        }
    }

    private fun loadSavedAccounts() {
        val binding = bindingSafe ?: return
        val savedAccounts = AccountManager.getSavedAccounts(requireContext())

        if (savedAccounts.isEmpty()) {
            binding.layoutSavedAccounts.visibility = View.GONE
            return
        }

        binding.layoutSavedAccounts.visibility = View.VISIBLE
        binding.containerSavedAccounts.removeAllViews()

        savedAccounts.forEach { account ->
            val accountView = layoutInflater.inflate(R.layout.item_saved_account, binding.containerSavedAccounts, false)

            val cardAccount = accountView.findViewById<MaterialCardView>(R.id.cardAccount)
            val ivProfilePic = accountView.findViewById<ImageView>(R.id.ivProfilePic)
            val tvAccountName = accountView.findViewById<TextView>(R.id.tvAccountName)
            val tvAccountEmail = accountView.findViewById<TextView>(R.id.tvAccountEmail)
            val btnDeleteAccount = accountView.findViewById<ImageButton>(R.id.btnDeleteAccount)

            // Set account info
            tvAccountName.text = account.displayName ?: account.email.substringBefore('@')
            tvAccountEmail.text = account.email

            // Load profile picture
            if (account.photoUrl != null) {
                Glide.with(this)
                    .load(account.photoUrl)
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person)
                    .into(ivProfilePic)
            } else {
                ivProfilePic.setImageResource(R.drawable.ic_person)
            }

            // Click to fast sign-in
            cardAccount.setOnClickListener {
                fastSignIn(account.uid)
            }

            // Delete account
            btnDeleteAccount.setOnClickListener {
                AccountManager.removeAccount(requireContext(), account.uid)
                loadSavedAccounts()
            }

            binding.containerSavedAccounts.addView(accountView)
        }
    }

    private fun fastSignIn(uid: String) {
        val savedAccount = AccountManager.getSavedAccounts(requireContext()).firstOrNull { it.uid == uid }

        if (savedAccount == null) {
            showError("Account not found")
            return
        }

        // Check if this account is still logged in to Firebase
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser?.uid == uid) {
            // Already logged in
            findNavController().navigate(R.id.action_loginFragment_to_dashboardFragment)
            return
        }

        // Need to re-authenticate based on provider
        when (savedAccount.provider) {
            AuthProvider.GOOGLE -> {
                showError("Please sign in with Google to continue")
                // Trigger Google sign-in automatically
                signInWithGoogle()
            }
            AuthProvider.FACEBOOK -> {
                showError("Please sign in with Facebook to continue")
                // Trigger Facebook sign-in automatically
                signInWithFacebook()
            }
            AuthProvider.EMAIL -> {
                // For email, pre-fill the email field
                val binding = bindingSafe ?: return
                binding.etEmail.setText(savedAccount.email)
                binding.etPassword.requestFocus()
                showError("Please enter your password")
            }
        }
    }

    private fun signInWithGoogle() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val credentialManager = CredentialManager.create(requireContext())
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(getString(R.string.default_web_client_id))
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val result = credentialManager.getCredential(requireActivity(), request)
                val credential = result.credential

                if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    firebaseAuthWithGoogle(googleIdTokenCredential.idToken)
                } else {
                    showError("Unexpected credential type format")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("Google Sign-In failed: ${e.message}")
            }
        }
    }

    private fun signInWithFacebook() {
        LoginManager.getInstance().logInWithReadPermissions(this, listOf("email", "public_profile"))
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val authCredential = GoogleAuthProvider.getCredential(idToken, null)
        FirebaseAuth.getInstance().signInWithCredential(authCredential)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val user = FirebaseAuth.getInstance().currentUser
                    if (user != null && isAdded) {
                        AccountManager.saveAccount(requireContext(), user, AuthProvider.GOOGLE)
                    }
                    navigateToDashboard()
                } else {
                    showError("Google authentication failed: ${task.exception?.message}")
                }
            }
    }

    private fun firebaseAuthWithFacebook(token: String) {
        val credential = FacebookAuthProvider.getCredential(token)
        FirebaseAuth.getInstance().signInWithCredential(credential)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val user = FirebaseAuth.getInstance().currentUser
                    if (user != null && isAdded) {
                        AccountManager.saveAccount(requireContext(), user, AuthProvider.FACEBOOK)
                    }
                    navigateToDashboard()
                } else {
                    showError("Facebook authentication failed: ${task.exception?.message}")
                }
            }
    }

    private fun navigateToDashboard() {
        if (isAdded && view != null) {
            findNavController().navigate(R.id.action_loginFragment_to_dashboardFragment)
        }
    }

    private fun showError(message: String) {
        if (context != null && view != null && isAdded) {
            Snackbar.make(requireView(), message, Snackbar.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        callbackManager.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
