package me.capcom.smsgateway.ui.tinytot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.capcom.smsgateway.MainActivity
import me.capcom.smsgateway.R
import me.capcom.smsgateway.databinding.DialogUpiReasonBinding
import me.capcom.smsgateway.databinding.DialogUpiVerifyBinding
import me.capcom.smsgateway.databinding.FragmentUpiPaymentsBinding
import me.capcom.smsgateway.modules.tinytot.UpiAmounts
import me.capcom.smsgateway.modules.tinytot.domain.CandidateSelector
import me.capcom.smsgateway.modules.tinytot.domain.UpiAlert
import me.capcom.smsgateway.modules.tinytot.domain.UpiIntent
import me.capcom.smsgateway.modules.tinytot.vm.UpiPaymentsViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

/** Counter screen: open UPI payment intents and bank credits that need a person. */
class UpiPaymentsFragment : Fragment() {
    private val viewModel: UpiPaymentsViewModel by viewModel()

    private var _binding: FragmentUpiPaymentsBinding? = null
    private val binding get() = _binding!!

    private lateinit var themedContext: Context

    private val adapter = UpiPaymentsAdapter(
        onVerify = ::showVerifyDialog,
        onReject = ::showRejectDialog,
        onIgnore = ::showIgnoreDialog,
    )

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            updateBanner()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        themedContext = ContextThemeWrapper(requireContext(), R.style.Theme_TinyTot_Payments)
        _binding = FragmentUpiPaymentsBinding.inflate(
            inflater.cloneInContext(themedContext), container, false
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recycler.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh(showSpinner = true) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.messages.collect(::showMessage) }
            }
        }

        // Auto refresh only while this page is the visible tab.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                updateBanner()
                var first = true
                while (true) {
                    viewModel.refresh(showSpinner = first && !viewModel.state.value.loadedOnce)
                    first = false
                    delay(AUTO_REFRESH_MS)
                }
            }
        }
    }

    private fun render(state: UpiPaymentsViewModel.State) {
        binding.swipeRefresh.isRefreshing = state.refreshing
        binding.swipeRefresh.isEnabled = state.configured
        updateBanner(state.configured)

        binding.statusText.text = when {
            state.error != null -> requireContext().describeUpiError(state.error)
            state.lastLoadedAt != null -> getString(
                R.string.tinytot_updated_at,
                DateUtils.formatDateTime(
                    requireContext(),
                    state.lastLoadedAt,
                    DateUtils.FORMAT_SHOW_TIME,
                )
            )

            state.configured -> getString(R.string.tinytot_loading)
            else -> ""
        }
        binding.statusText.setTextColor(
            if (state.error != null) {
                resolveColor(com.google.android.material.R.attr.colorError)
            } else {
                resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
            }
        )

        if (!state.configured) {
            adapter.submitList(emptyList())
            return
        }
        if (!state.loadedOnce) return

        val rows = buildList {
            add(
                UpiPaymentsAdapter.Row.Header(
                    "${getString(R.string.tinytot_open_payments)} (${state.intents.size})"
                )
            )
            if (state.intents.isEmpty()) {
                add(UpiPaymentsAdapter.Row.Empty(getString(R.string.tinytot_no_open_payments)))
            }
            state.intents.forEach {
                add(UpiPaymentsAdapter.Row.Intent(it, it.id in state.busyIds))
            }

            add(
                UpiPaymentsAdapter.Row.Header(
                    "${getString(R.string.tinytot_unmatched_credits)} (${state.alerts.size})"
                )
            )
            if (state.alerts.isEmpty()) {
                add(UpiPaymentsAdapter.Row.Empty(getString(R.string.tinytot_no_unmatched_credits)))
            }
            state.alerts.forEach {
                add(UpiPaymentsAdapter.Row.Alert(it, it.id in state.busyIds))
            }
        }
        adapter.submitList(rows)
    }

    private fun updateBanner(configured: Boolean = viewModel.state.value.configured) {
        val binding = _binding ?: return
        val missing = missingPermissions()

        when {
            !configured -> {
                binding.bannerText.setText(R.string.tinytot_not_configured)
                binding.bannerAction.setText(R.string.tinytot_open_settings)
                binding.bannerAction.setOnClickListener {
                    (activity as? MainActivity)?.selectTab(MainActivity.TAB_INDEX_SETTINGS)
                }
                binding.bannerCard.visibility = View.VISIBLE
            }

            missing.isNotEmpty() -> {
                binding.bannerText.setText(R.string.tinytot_permissions_missing)
                binding.bannerAction.setText(R.string.tinytot_grant_permissions)
                binding.bannerAction.setOnClickListener {
                    permissionsLauncher.launch(missing.toTypedArray())
                }
                binding.bannerCard.visibility = View.VISIBLE
            }

            else -> binding.bannerCard.visibility = View.GONE
        }
    }

    private fun missingPermissions(): List<String> {
        val required = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required += Manifest.permission.POST_NOTIFICATIONS
        }
        return required.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showMessage(message: UpiPaymentsViewModel.Message) {
        val view = _binding?.root ?: return
        val text = when (message) {
            is UpiPaymentsViewModel.Message.Failure -> requireContext().describeUpiError(message.error)
            is UpiPaymentsViewModel.Message.Success -> when (message.kind) {
                UpiPaymentsViewModel.Message.Kind.Verified -> UpiAmounts.parse(message.amount)
                    ?.let { getString(R.string.tinytot_verified_amount, UpiAmounts.format(it)) }
                    ?: getString(R.string.tinytot_verified)

                UpiPaymentsViewModel.Message.Kind.Rejected -> getString(R.string.tinytot_rejected)
                UpiPaymentsViewModel.Message.Kind.Ignored -> getString(R.string.tinytot_ignored)
            }
        }
        val duration = if (message is UpiPaymentsViewModel.Message.Failure) {
            Snackbar.LENGTH_LONG
        } else {
            Snackbar.LENGTH_SHORT
        }
        Snackbar.make(view, text, duration).show()
    }

    private fun showVerifyDialog(intent: UpiIntent) {
        val selection = CandidateSelector.select(intent, viewModel.state.value.alerts)
        val dialogBinding = DialogUpiVerifyBinding.inflate(LayoutInflater.from(themedContext))
        val manualId = View.generateViewId()
        val optionIds = selection.options.map { View.generateViewId() }

        if (selection.options.isEmpty()) {
            dialogBinding.hint.setText(R.string.tinytot_verify_no_credits)
            dialogBinding.creditGroup.visibility = View.GONE
        } else {
            dialogBinding.hint.setText(R.string.tinytot_verify_pick)
            selection.options.forEachIndexed { index, option ->
                dialogBinding.creditGroup.addView(radioButton(optionIds[index], describeOption(option)))
            }
            dialogBinding.creditGroup.addView(
                radioButton(manualId, getString(R.string.tinytot_verify_manual))
            )
            dialogBinding.creditGroup.setOnCheckedChangeListener { _, checkedId ->
                dialogBinding.manualGroup.visibility =
                    if (checkedId == manualId) View.VISIBLE else View.GONE
            }
            dialogBinding.creditGroup.check(
                selection.preselected?.let { optionIds[it] } ?: manualId
            )
        }
        dialogBinding.manualGroup.visibility =
            if (selection.options.isEmpty() || selection.preselected == null) View.VISIBLE else View.GONE

        (intent.claimAmount ?: intent.amountExpected)?.let {
            dialogBinding.amountInput.setText(it.stripTrailingZeros().toPlainString())
        }
        intent.claimUtr?.let { dialogBinding.utrInput.setText(it) }

        val dialog = MaterialAlertDialogBuilder(themedContext)
            .setTitle(getString(R.string.tinytot_verify_title, UpiAmounts.format(intent.amountExpected)))
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.tinytot_cancel, null)
            .setPositiveButton(R.string.tinytot_next, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val checked = dialogBinding.creditGroup.checkedRadioButtonId
                val optionIndex = optionIds.indexOf(checked)
                if (selection.options.isNotEmpty() && optionIndex >= 0) {
                    dialog.dismiss()
                    confirmVerify(intent, selection.options[optionIndex].alert, null, null)
                    return@setOnClickListener
                }

                val amount = UpiAmounts.parseManual(dialogBinding.amountInput.text?.toString())
                val utr = dialogBinding.utrInput.text?.toString()?.trim().orEmpty()
                dialogBinding.amountLayout.error =
                    if (amount == null) getString(R.string.tinytot_amount_invalid) else null
                val utrValid = utr.isEmpty() || UTR.matches(utr)
                dialogBinding.utrLayout.error =
                    if (utrValid) null else getString(R.string.tinytot_utr_invalid)

                if (amount != null && utrValid) {
                    dialog.dismiss()
                    confirmVerify(intent, null, amount, utr.ifEmpty { null })
                }
            }
        }
        dialog.show()
    }

    private fun confirmVerify(intent: UpiIntent, alert: UpiAlert?, amount: String?, utr: String?) {
        val reference = intent.reference ?: intent.title ?: getString(R.string.tinytot_payment)
        val message = if (alert != null) {
            getString(
                R.string.tinytot_confirm_verify_credit,
                UpiAmounts.format(alert.amount),
                alert.payerName ?: getString(R.string.tinytot_unknown_payer),
                reference,
            )
        } else {
            getString(
                R.string.tinytot_confirm_verify_manual,
                reference,
                UpiAmounts.format(UpiAmounts.parse(amount)),
            )
        }

        MaterialAlertDialogBuilder(themedContext)
            .setTitle(R.string.tinytot_confirm_verify_title)
            .setMessage(message)
            .setNegativeButton(R.string.tinytot_cancel, null)
            .setPositiveButton(R.string.tinytot_mark_paid) { _, _ ->
                viewModel.verify(intent, alert, amount, utr)
            }
            .show()
    }

    private fun showRejectDialog(intent: UpiIntent) {
        val reference = intent.reference ?: UpiAmounts.format(intent.amountExpected)
        showReasonDialog(
            getString(R.string.tinytot_reject_title, reference),
            getString(R.string.tinytot_reject),
        ) { reason -> viewModel.reject(intent, reason) }
    }

    private fun showIgnoreDialog(alert: UpiAlert) {
        showReasonDialog(
            getString(R.string.tinytot_ignore_title, UpiAmounts.format(alert.amount)),
            getString(R.string.tinytot_ignore),
        ) { reason -> viewModel.ignore(alert, reason) }
    }

    private fun showReasonDialog(title: String, action: String, onReason: (String) -> Unit) {
        val dialogBinding = DialogUpiReasonBinding.inflate(LayoutInflater.from(themedContext))
        val dialog = MaterialAlertDialogBuilder(themedContext)
            .setTitle(title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.tinytot_cancel, null)
            .setPositiveButton(action, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val reason = dialogBinding.reasonInput.text?.toString()?.trim().orEmpty()
                if (reason.isEmpty()) {
                    dialogBinding.reasonLayout.error = getString(R.string.tinytot_reason_required)
                    return@setOnClickListener
                }
                dialog.dismiss()
                onReason(reason)
            }
        }
        dialog.show()
    }

    private fun describeOption(option: CandidateSelector.Option): String {
        val alert = option.alert
        return listOfNotNull(
            UpiAmounts.format(alert.amount),
            alert.payerName,
            alert.utr?.let { getString(R.string.tinytot_utr, it) },
            alert.receivedAt?.let {
                DateUtils.getRelativeTimeSpanString(
                    it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                ).toString()
            },
            if (option.suggested) getString(R.string.tinytot_suggested) else null,
        ).joinToString(" · ")
    }

    private fun radioButton(id: Int, text: String) = RadioButton(themedContext).apply {
        this.id = id
        this.text = text
        minHeight = (48 * resources.displayMetrics.density).toInt()
    }

    private fun resolveColor(attr: Int): Int {
        val value = android.util.TypedValue()
        themedContext.theme.resolveAttribute(attr, value, true)
        return value.data
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val AUTO_REFRESH_MS = 20_000L
        private val UTR = Regex("^\\d{12}$")

        fun newInstance() = UpiPaymentsFragment()
    }
}
