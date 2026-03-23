package com.watermonitor.app.ui.auth

import android.graphics.drawable.Animatable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
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
            // PLACEHOLDER BEHAVIOR:
            // Just simulate a successful registration, go back to login screen.
            
            /* TODO: Firebase Implementation
            val email = binding.etEmail.text.toString()
            val pass = binding.etPassword.text.toString()
            val user = binding.etUsername.text.toString()
            FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, pass)
                .addOnSuccessListener { authResult ->
                    // Store user profile details in Firestore/RTDB
                    // findNavController().navigate(R.id.action_registerFragment_to_dashboardFragment) 
                    // or popBackStack()
                }
            */
            
            // For placeholder, we will just return to login
            findNavController().popBackStack()
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
