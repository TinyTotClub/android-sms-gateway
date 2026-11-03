package me.capcom.smsgateway.ui.tinytot

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import me.capcom.smsgateway.R
import me.capcom.smsgateway.databinding.ItemUpiAlertBinding
import me.capcom.smsgateway.databinding.ItemUpiEmptyBinding
import me.capcom.smsgateway.databinding.ItemUpiHeaderBinding
import me.capcom.smsgateway.databinding.ItemUpiIntentBinding
import me.capcom.smsgateway.modules.tinytot.UpiAmounts
import me.capcom.smsgateway.modules.tinytot.domain.UpiAlert
import me.capcom.smsgateway.modules.tinytot.domain.UpiIntent

class UpiPaymentsAdapter(
    private val onVerify: (UpiIntent) -> Unit,
    private val onReject: (UpiIntent) -> Unit,
    private val onIgnore: (UpiAlert) -> Unit,
) : ListAdapter<UpiPaymentsAdapter.Row, RecyclerView.ViewHolder>(Diff) {

    sealed class Row(val key: String) {
        data class Header(val title: String) : Row("h:$title")
        data class Empty(val text: String) : Row("e:$text")
        data class Intent(val intent: UpiIntent, val busy: Boolean) : Row("i:${intent.id}")
        data class Alert(val alert: UpiAlert, val busy: Boolean) : Row("a:${alert.id}")
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is Row.Header -> TYPE_HEADER
        is Row.Empty -> TYPE_EMPTY
        is Row.Intent -> TYPE_INTENT
        is Row.Alert -> TYPE_ALERT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(ItemUpiHeaderBinding.inflate(inflater, parent, false))
            TYPE_EMPTY -> EmptyHolder(ItemUpiEmptyBinding.inflate(inflater, parent, false))
            TYPE_INTENT -> IntentHolder(ItemUpiIntentBinding.inflate(inflater, parent, false))
            else -> AlertHolder(ItemUpiAlertBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder as HeaderHolder).binding.title.text = row.title
            is Row.Empty -> (holder as EmptyHolder).binding.text.text = row.text
            is Row.Intent -> (holder as IntentHolder).bind(row)
            is Row.Alert -> (holder as AlertHolder).bind(row)
        }
    }

    class HeaderHolder(val binding: ItemUpiHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    class EmptyHolder(val binding: ItemUpiEmptyBinding) : RecyclerView.ViewHolder(binding.root)

    inner class IntentHolder(private val binding: ItemUpiIntentBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row.Intent) {
            val intent = row.intent
            val context = binding.root.context

            binding.amount.text = UpiAmounts.format(intent.amountExpected)
            binding.status.text = context.getString(
                when (intent.status) {
                    UpiIntent.Status.Pending -> R.string.tinytot_status_pending
                    UpiIntent.Status.Claimed -> R.string.tinytot_status_claimed
                    UpiIntent.Status.Mismatch -> R.string.tinytot_status_mismatch
                    UpiIntent.Status.Failed -> R.string.tinytot_status_failed
                    UpiIntent.Status.Other -> R.string.tinytot_status_other
                }
            )

            val title = intent.title ?: context.getString(R.string.tinytot_payment)
            binding.title.text = intent.studentId
                ?.takeIf { intent.title == null }
                ?.let { context.getString(R.string.tinytot_student, it) }
                ?: title

            binding.meta.text = listOfNotNull(
                intent.reference?.let { context.getString(R.string.tinytot_ref, it) },
                intent.createdAt?.let { relative(it) },
            ).joinToString(" · ")

            val claimText = when {
                intent.claimAmount != null && intent.claimUtr != null -> context.getString(
                    R.string.tinytot_claim, UpiAmounts.format(intent.claimAmount), intent.claimUtr
                )

                intent.claimAmount != null -> context.getString(
                    R.string.tinytot_claim_no_utr, UpiAmounts.format(intent.claimAmount)
                )

                intent.claimUtr != null -> context.getString(
                    R.string.tinytot_claim_utr_only, intent.claimUtr
                )

                else -> null
            }
            binding.claim.text = claimText
            binding.claim.visibility = if (claimText == null) View.GONE else View.VISIBLE

            binding.verifyButton.visibility =
                if (intent.status == UpiIntent.Status.Failed) View.GONE else View.VISIBLE
            binding.verifyButton.isEnabled = !row.busy
            binding.rejectButton.isEnabled = !row.busy
            binding.verifyButton.setOnClickListener { onVerify(intent) }
            binding.rejectButton.setOnClickListener { onReject(intent) }
        }
    }

    inner class AlertHolder(private val binding: ItemUpiAlertBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row.Alert) {
            val alert = row.alert
            val context = binding.root.context

            binding.amount.text = UpiAmounts.format(alert.amount)
            binding.status.text = context.getString(
                if (alert.isAmbiguous) R.string.tinytot_status_ambiguous
                else R.string.tinytot_status_unmatched
            )
            binding.payer.text = alert.payerName ?: context.getString(R.string.tinytot_unknown_payer)
            binding.meta.text = listOfNotNull(
                alert.utr?.let { context.getString(R.string.tinytot_utr, it) },
                alert.receivedAt?.let { relative(it) },
            ).joinToString(" · ")

            binding.ignoreButton.isEnabled = !row.busy
            binding.ignoreButton.setOnClickListener { onIgnore(alert) }
        }
    }

    private fun relative(time: Long): CharSequence = DateUtils.getRelativeTimeSpanString(
        time,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    )

    private object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row) = oldItem.key == newItem.key
        override fun areContentsTheSame(oldItem: Row, newItem: Row) = oldItem == newItem
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_EMPTY = 1
        private const val TYPE_INTENT = 2
        private const val TYPE_ALERT = 3
    }
}
