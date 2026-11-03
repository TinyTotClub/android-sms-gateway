package me.capcom.smsgateway.modules.tinytot

/** Device ids sent as `x-upi-device`: `[A-Za-z0-9._-]{1,64}`. */
object DeviceIds {
    private val VALID = Regex("^[A-Za-z0-9._-]{1,64}$")
    private const val FALLBACK = "upi-verifier"

    fun isValid(value: String?): Boolean = value != null && VALID.matches(value)

    fun slug(model: String?): String {
        val slug = (model ?: "")
            .trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-')
            .take(64)
            .trim('-')
        return if (isValid(slug)) slug else FALLBACK
    }
}
