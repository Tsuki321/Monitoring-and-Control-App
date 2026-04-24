package com.watermonitor.app.ui.auth

import android.graphics.drawable.Animatable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.google.firebase.auth.FacebookAuthProvider
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import android.content.Intent
import com.watermonitor.app.R
import com.watermonitor.app.databinding.FragmentLoginBinding
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = bindingSafe ?: return

        // Start SVG animation
        val drawable = binding.ivLoginAnim.drawable
        if (drawable is Animatable) {
            drawable.start()
        }

        // Register CallbackManager for Facebook Login
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
                    } else {
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
                    bindingSafe?.let {
                        findNavController().navigate(R.id.action_loginFragment_to_dashboardFragment)
                    }
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
                    bindingSafe?.let {
                        findNavController().navigate(R.id.action_loginFragment_to_dashboardFragment)
                    }
                } else {
                    showError("Facebook authentication failed: ${task.exception?.message}")
                }
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
