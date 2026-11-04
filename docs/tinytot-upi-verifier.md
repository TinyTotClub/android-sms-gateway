# TinyTot UPI Verifier

This fork of SMSGate runs on the school-counter phone that holds the SIM registered to the
school's Punjab National Bank (PNB) account. It does two jobs:

1. **Forwards PNB bank SMS** to the TinyTot Supabase Edge Function `upi-verifier`. The server
   parses each SMS, matches the credit to an open UPI payment, and marks the bill paid.
2. **Payments tab.** Staff can see open payments and bank credits that did not match, and can
   verify, reject or ignore them by hand.

Only SMS from PNB sender ids are forwarded. OTPs and personal SMS never leave the phone. The
filter is in `modules/tinytot/PnbSenderFilter.kt` and matches the server's own check.

The app is installed as `me.capcom.smsgateway.tinytot` with the label "TinyTot UPI Verifier", so
it can sit next to the upstream SMSGate app.

## Build

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # not committed
# Firebase config is required by the build. The TinyTot features do not use Firebase,
# so the placeholder works unless you also need the upstream cloud gateway:
cp docs/google-services.placeholder.json app/google-services.json
JAVA_HOME=<JDK 17> ./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
JAVA_HOME=<JDK 17> ./gradlew test
```

## Set up the counter phone

1. **Install the APK** (`adb install app-debug.apk` or copy it to the phone and open it).
2. **Grant permissions.** Open the app. The **Payments** tab shows a banner while SMS or
   notification permission is missing. Tap **Grant** and allow *SMS* and *Notifications*.
3. **Disable battery optimisation.** Go to Settings tab → System → Battery optimization →
   allow. On Xiaomi, Oppo, Vivo or Samsung, also allow autostart/background activity for the
   app in the phone's own settings.
4. **Disable RCS chat in Google Messages.** Go to Messages → Settings → RCS chats → off. Bank
   messages that arrive as RCS do not reach SMS apps. They must arrive as plain SMS.
5. **Enter the server details.** Go to Settings tab → **TinyTot UPI**:
   - **Verifier URL**: `https://<project-ref>.supabase.co/functions/v1/upi-verifier`
   - **API key**: the value of the `UPI_VERIFIER_API_KEY` Supabase secret. It is stored
     encrypted with an Android Keystore key and is never shown again. Leaving the field
     blank keeps the current key.
   - **Device id**: by default this comes from the phone model (for example `Pixel-7a`).
     Change it if you like. Use 1–64 characters from `A-Z a-z 0-9 . _ -`.
   - **Forward PNB SMS**: on.
6. **Tap Test connection.** It sends a signed `ping`. On success it shows the school id. It
   warns if the phone clock differs from the server by more than a minute. The server rejects
   requests more than 5 minutes off ("Phone clock is wrong"), so keep *automatic date & time* on.
7. Optional: tap **Resend recent PNB SMS** to forward the last 24 h of PNB SMS again, for
   example after first setup or a long outage. The server drops duplicates by message hash.

The **Status** line in the TinyTot UPI settings shows the last successful sync and the last
error. The **Home** tab shows a *PNB SMS forwarding* card (Active / Off / Not set up).

Forwarding does not need **Start service**: that button and *Start on boot* only run the
optional upstream local/cloud servers and are hidden while both are off. Whenever *Forward PNB
SMS* is on and the URL and key are set, the app enables its SMS receiver at every app start
(including boot) and on every settings change, and stopping the upstream service leaves it on.

## How forwarding works

- `ReceiverService` handles every incoming SMS. That includes the static `SMS_RECEIVED`
  receiver, so it works while the app is closed. For each new text SMS it calls
  `UpiSmsForwarder.onIncomingSms`. If the sender is not PNB, the forwarder returns at once.
- PNB SMS go into the app's existing persistent **webhook queue** (Room `webhook_queue`).
  They use the marker URL `tinytot-upi://ingest_sms`, so they appear in Settings →
  Webhooks → Queue.
- `WebhookQueueProcessorWorker` takes those entries out of the normal webhook path and gives
  them to `UpiQueueDelivery`. That class sends them as signed `ingest_sms` batches of up to 50
  messages. Retries use the queue's exponential backoff and the Webhooks → *Retry count*
  setting. A network error or 5xx is retried. A 400 is dropped. `stored`, `duplicate`,
  `ignored_sender` and `invalid` results count as delivered.
- Each newly stored credit gives a local notification: "₹X payment verified" when it was
  matched and verified, otherwise "Credit needs review". The amount in the notification is
  read from the SMS on the phone, for display only.

## Payments tab

- Lists open intents (pending / payer says paid / amount mismatch / failed) and unmatched or
  ambiguous credits from `list_open` (last 72 h). Pull down to refresh. While the tab is
  visible, it also refreshes every 20 s.
- **Verify**: pick one of the bank credits. Credits with the payer's claimed UTR, credits the
  server lists as candidates, and credits within ₹10 of the expected or claimed amount are
  marked *Suggested*, and the best one is preselected. Or choose *Enter amount manually* and
  type the amount and an optional 12-digit UTR. A confirm dialog appears before the bill is
  marked paid.
- **Reject** asks for a reason. **Ignore** on a credit also asks for a reason.

## Security notes

- Every request carries `x-upi-timestamp`, `x-upi-signature` and `x-upi-device`. The
  signature is `hex(HMAC-SHA256(apiKey, "<ts>.<raw body>"))`, computed over the exact bytes
  that are sent.
- A successful response is accepted only if its own `x-upi-signature` checks out. The check
  uses `MessageDigest.isEqual`. Unsigned 401/503 replies are always treated as errors.

## Upstream merges

The TinyTot code is in `modules/tinytot/` and `ui/tinytot/`, with its resources in
`*_tinytot.xml`, `item_upi_*`, `dialog_upi_*`, `fragment_upi_payments.xml` and
`tinytot_preferences.xml`. Upstream files have only small hooks: `App.kt` (module
registration), `MainActivity.kt` (Payments tab), `ReceiverService.kt` (forward hook),
`WebhookQueueProcessorWorker.kt` (batch delivery), `root_preferences.xml`,
`AndroidManifest.xml` (`POST_NOTIFICATIONS`), `strings.xml` (`app_name`) and `app/build.gradle`
(`applicationIdSuffix`, two AndroidX dependencies).
