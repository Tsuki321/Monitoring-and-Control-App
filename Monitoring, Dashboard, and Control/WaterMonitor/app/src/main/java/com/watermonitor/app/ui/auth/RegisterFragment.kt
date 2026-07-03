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
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.auth.FacebookAuthProvider
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import android.content.Intent
import com.watermonitor.app.R
import com.watermonitor.app.data.model.AuthProvider
import com.watermonitor.app.databinding.FragmentRegisterBinding
import com.watermonitor.app.utils.AccountManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class RegisterFragment : Fragment() {

    private var _binding: FragmentRegisterBinding? = null
    private val bindingSafe get() = _binding
    private val callbackManager = CallbackManager.Factory.create()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRegisterBinding.inflate(inflater, container, false)
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
        val drawable = binding.ivRegisterAnim.drawable
        if (drawable is Animatable) {
            drawable.start()
        }

        binding.btnRegister.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()
            val user = binding.etUsername.text.toString().trim()

            if (email.isEmpty() || pass.isEmpty() || user.isEmpty()) {
                showError("Please fill all fields")
                return@setOnClickListener
            }

            binding.btnRegister.isEnabled = false

            val auth = FirebaseAuth.getInstance()
            auth.createUserWithEmailAndPassword(email, pass)
                .addOnSuccessListener { authResult ->
                    val firebaseUser = authResult.user
                    if (firebaseUser != null) {
                        val profileUpdates = UserProfileChangeRequest.Builder()
                            .setDisplayName(user)
                            .build()

                        firebaseUser.updateProfile(profileUpdates)
                            .addOnCompleteListener { profileTask ->
                                val currentBinding = bindingSafe ?: return@addOnCompleteListener
                                currentBinding.btnRegister.isEnabled = true

                                if (profileTask.isSuccessful) {
                                    firebaseUser.sendEmailVerification()
                                    FirebaseAuth.getInstance().signOut()
                                    showError(getString(R.string.auth_verify_email_sent))
                                    findNavController().popBackStack()
                                } else {
                                    showError("Error setting username: ${profileTask.exception?.message}")
                                }
                            }
                    } else {
                        val currentBinding = bindingSafe ?: return@addOnSuccessListener
                        currentBinding.btnRegister.isEnabled = true
                        showError("Registration successful!")
                        findNavController().popBackStack()
                    }
                }
                .addOnFailureListener { e ->
                    val currentBinding = bindingSafe ?: return@addOnFailureListener
                    currentBinding.btnRegister.isEnabled = true
                    showError("Error: ${e.message}")
                }
        }

        binding.tvGoToLogin.setOnClickListener {
            findNavController().navigate(R.id.action_registerFragment_to_loginFragment)
        }

        // Google Sign In using CredentialManager
        binding.btnGoogleRegister.setOnClickListener {
            signInWithGoogle()
        }

        // Facebook Sign In using Firebase OAuthProvider
        binding.btnFacebookRegister.setOnClickListener {
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
            findNavController().navigate(R.id.action_registerFragment_to_dashboardFragment)
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
