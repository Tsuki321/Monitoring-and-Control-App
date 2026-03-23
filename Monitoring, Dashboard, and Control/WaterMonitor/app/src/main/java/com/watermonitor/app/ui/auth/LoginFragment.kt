package com.watermonitor.app.ui.auth

import android.graphics.drawable.Animatable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.firestore.FirebaseFirestore
import com.watermonitor.app.R
import com.watermonitor.app.databinding.FragmentLoginBinding

class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Start SVG animation
        val drawable = binding.ivLoginAnim.drawable
        if (drawable is Animatable) {
            drawable.start()
        }

        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()

            if (email.isEmpty() || pass.isEmpty()) {
                Snackbar.make(view, "Please enter email and password", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnLogin.isEnabled = false

            val db = FirebaseFirestore.getInstance()
            db.collection("users").document(email.lowercase()).get()
                .addOnSuccessListener { document ->
                    binding.btnLogin.isEnabled = true
                    if (context != null) {
                        if (document.exists() && document.getString("password") == pass) {
                            Snackbar.make(view, "Login successful!", Snackbar.LENGTH_SHORT).show()
                            findNavController().navigate(R.id.action_loginFragment_to_dashboardFragment)
                        } else {
                            Snackbar.make(view, "Invalid email or password", Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
                .addOnFailureListener { e ->
                    binding.btnLogin.isEnabled = true
                    if (context != null) {
                        Snackbar.make(view, "Error: ${e.message}", Snackbar.LENGTH_LONG).show()
                    }
                }
        }

        binding.tvGoToRegister.setOnClickListener {
            findNavController().navigate(R.id.action_loginFragment_to_registerFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
