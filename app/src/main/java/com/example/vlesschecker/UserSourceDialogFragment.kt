package com.example.vlesschecker

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import com.example.vlesschecker.databinding.DialogUserSourceBinding

class UserSourceDialogFragment : DialogFragment() {
    private var _binding: DialogUserSourceBinding? = null
    private val binding get() = _binding!!
    private var sourceId: String? = null
    private var onSavedListener: ((UserSource) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceId = arguments?.getString(ARG_SOURCE_ID)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogUserSourceBinding.inflate(LayoutInflater.from(requireContext()))

        val source = sourceId?.let { UserSourceManager.getById(requireContext(), it) }

        binding.urlEditText.setText(source?.url ?: "")
        binding.nameEditText.setText(source?.name ?: "")

        val dialog = AlertDialog.Builder(requireContext())
            .setView(binding.root)
            .setTitle(if (source == null) "Добавить источник" else "Редактировать источник")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                saveSource()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        // Validate URL
        val urlWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                validateInput()
            }
        }
        binding.urlEditText.addTextChangedListener(urlWatcher)
        validateInput()

        return dialog
    }

    private fun validateInput() {
        val url = binding.urlEditText.text.toString().trim()
        val isValid = url.startsWith("http://") || url.startsWith("https://")
        binding.urlInputLayout.error = if (!isValid && url.isNotBlank()) "URL должен начинаться с http:// или https://" else null
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = isValid
    }

    private fun saveSource() {
        val url = binding.urlEditText.text.toString().trim()
        val name = binding.nameEditText.text.toString().trim()

        val source = if (sourceId != null) {
            UserSourceManager.getById(requireContext(), sourceId!!)?.copy(
                name = name,
                url = url
            ) ?: UserSource(name = name, url = url)
        } else {
            UserSource(name = name, url = url)
        }

        onSavedListener?.invoke(source)
    }

    fun setOnSavedListener(listener: (UserSource) -> Unit) {
        this.onSavedListener = listener
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_SOURCE_ID = "source_id"

        fun newInstance(sourceId: String?): UserSourceDialogFragment {
            return UserSourceDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SOURCE_ID, sourceId)
                }
            }
        }
    }
}