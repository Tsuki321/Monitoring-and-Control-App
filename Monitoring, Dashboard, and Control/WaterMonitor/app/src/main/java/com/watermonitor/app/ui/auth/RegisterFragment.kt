package com.watermonitor.app.ui.auth

import android.graphics.drawable.Animatable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.watermonitor.app.R
import com.watermonitor.app.databinding.FragmentRegisterBinding

class RegisterFragment : Fragment() {

    private var _binding: FragmentRegisterBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRegisterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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
                Snackbar.make(view, "Please fill all fields", Snackbar.LENGTH_SHORT).show()
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
                                binding.btnRegister.isEnabled = true
                                if (context != null) {
                                    if (profileTask.isSuccessful) {
                                        Snackbar.make(view, "Registration successful!", Snackbar.LENGTH_SHORT).show()
                                        findNavController().popBackStack()
                                    } else {
                                        Snackbar.make(view, "Error setting username: ${profileTask.exception?.message}", Snackbar.LENGTH_LONG).show()
                                    }
                                }
                            }
                    } else {
                        binding.btnRegister.isEnabled = true
                        if (context != null) {
                            Snackbar.make(view, "Registration successful!", Snackbar.LENGTH_SHORT).show()
                            findNavController().popBackStack()
                        }
                    }
                }
                .addOnFailureListener { e ->
                    binding.btnRegister.isEnabled = true
                    if (context != null) {
                        Snackbar.make(view, "Error: ${e.message}", Snackbar.LENGTH_LONG).show()
                    }
                }
        }

        binding.tvGoToLogin.setOnClickListener {
            findNavController().navigate(R.id.action_registerFragment_to_loginFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
