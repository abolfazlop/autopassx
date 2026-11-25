package dev.autopassx.autofill

import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.text.InputType
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import dev.autopassx.security.EncryptedVault
import org.json.JSONObject
import java.util.Locale

/**
 * Android Autofill integration.
 *
 * Important: Android only sends a SaveRequest when a prior FillResponse contains SaveInfo,
 * the required fields changed, and the user accepts the system save prompt. The service must
 * therefore return a response even when there is no matching saved login yet.
 */
class AutoPassAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onSuccess(null)
            return
        }

        try {
            val structure = request.fillContexts.lastOrNull()?.structure
            val packageName = structure?.activityComponent?.packageName
            if (structure == null || packageName.isNullOrBlank() || packageName == this.packageName) {
                callback.onSuccess(null)
                return
            }

            val fields = collectFields(structure)
            val password = fields.firstOrNull { it.kind == FieldKind.PASSWORD }
            val username = fields.firstOrNull { it.kind == FieldKind.USERNAME }
                ?: fields.firstOrNull {
                    it.kind == FieldKind.OTHER_TEXT &&
                        (password == null || fields.indexOf(it) < fields.indexOf(password))
                }

            // Do not stop just because the vault has no match. SaveInfo is what enables
            // Android to ask the user whether the newly entered credentials should be saved.
            if (password == null && username == null) {
                callback.onSuccess(null)
                return
            }

            val domain = password?.domain ?: username?.domain
                ?: fields.firstNotNullOfOrNull { it.domain }

            val records = EncryptedVault(this).all()
                .filter { record ->
                    val savedDomain = normalizeDomain(record.optString("domain"))
                    if (!domain.isNullOrBlank()) {
                        savedDomain == domain
                    } else {
                        // Never offer a browser app's package-scoped credentials to every
                        // website. Package matching is only safe for native-app entries.
                        savedDomain.isNullOrBlank() &&
                            record.optString("package") == packageName
                    }
                }
                .take(MAX_SUGGESTIONS)

            val response = FillResponse.Builder()
            records.forEach { record ->
                val savedPassword = record.optString("password")
                val savedUsername = record.optString("username")
                if (savedPassword.isBlank() && savedUsername.isBlank()) return@forEach

                val label = record.optString("label").ifBlank {
                    savedUsername.ifBlank { domain ?: packageName }
                }
                val presentation = RemoteViews(
                    this.packageName,
                    android.R.layout.simple_list_item_1
                ).apply {
                    setTextViewText(
                        android.R.id.text1,
                        if (savedUsername.isBlank()) label else "$label · $savedUsername"
                    )
                }
                val dataset = Dataset.Builder(presentation).apply {
                    username?.let { field ->
                        if (savedUsername.isNotBlank()) {
                            setValue(field.id, AutofillValue.forText(savedUsername))
                        }
                    }
                    password?.let { field ->
                        if (savedPassword.isNotBlank()) {
                            setValue(field.id, AutofillValue.forText(savedPassword))
                        }
                    }
                }.build()
                response.addDataset(dataset)
            }

            // SaveInfo is deliberately attached even when records is empty. Without it,
            // onSaveRequest is never called for a first-time login.
            if (password != null) {
                val requiredIds = arrayOf(password.id)
                val dataTypes = SaveInfo.SAVE_DATA_TYPE_PASSWORD or
                    (if (username != null) SaveInfo.SAVE_DATA_TYPE_USERNAME else 0)
                val saveInfo = SaveInfo.Builder(dataTypes, requiredIds).apply {
                    if (username != null) setOptionalIds(arrayOf(username.id))
                    // Some web apps replace the login form without finishing the browser Activity.
                    // This lets Android trigger the save flow when all login fields disappear.
                    setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
                    setDescription("ذخیره اطلاعات ورود در AutoPass X")
                }.build()
                response.setSaveInfo(saveInfo)
            }

            callback.onSuccess(response.build())
        } catch (_: Exception) {
            callback.onSuccess(null)
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        try {
            val structure = request.fillContexts.lastOrNull()?.structure
            val packageName = structure?.activityComponent?.packageName
            if (structure == null || packageName.isNullOrBlank() || packageName == this.packageName) {
                callback.onFailure("Unable to identify the source application")
                return
            }

            val fields = collectFields(structure)
            val passwordField = fields.firstOrNull {
                it.kind == FieldKind.PASSWORD && it.value.isNotBlank()
            }
            if (passwordField == null) {
                callback.onFailure("Password field was not found")
                return
            }

            val usernameField = fields.firstOrNull {
                it.kind == FieldKind.USERNAME && it.value.isNotBlank()
            } ?: fields.firstOrNull {
                it.kind == FieldKind.OTHER_TEXT &&
                    it.value.isNotBlank() &&
                    fields.indexOf(it) < fields.indexOf(passwordField)
            }

            val usernameValue = usernameField?.value?.trim().orEmpty()
            if (usernameValue.isBlank()) {
                callback.onFailure("Username or email was not found. Add this login manually.")
                return
            }

            val domain = passwordField.domain
                ?: usernameField?.domain
                ?: fields.firstNotNullOfOrNull { it.domain }
            val appLabel = domain ?: runCatching {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, 0)
                ).toString()
            }.getOrDefault(packageName)

            val record = JSONObject()
                .put("package", packageName)
                .put("domain", domain.orEmpty())
                .put("label", appLabel)
                .put("username", usernameValue)
                .put("password", passwordField.value)

            val recordId = if (domain != null) {
                "web:$domain:$usernameValue"
            } else {
                "app:$packageName:$usernameValue"
            }

            EncryptedVault(this).put(recordId, record)
            callback.onSuccess()
        } catch (_: Exception) {
            callback.onFailure("Unable to save this login")
        }
    }

    private fun collectFields(structure: AssistStructure): List<NodeField> {
        val result = mutableListOf<NodeField>()
        for (windowIndex in 0 until structure.windowNodeCount) {
            scan(structure.getWindowNodeAt(windowIndex).rootViewNode, result, null)
        }
        return result
    }

    private fun scan(
        node: AssistStructure.ViewNode,
        output: MutableList<NodeField>,
        inheritedDomain: String?
    ) {
        val hints = node.autofillHints?.map { it.lowercase(Locale.ROOT) }.orEmpty()
        val inputType = node.inputType
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val entry = node.idEntry.orEmpty().lowercase(Locale.ROOT)
        val resourceName = node.idPackage?.let { "$it:$entry" }.orEmpty()
        val className = node.className.orEmpty()
        val domain = normalizeDomain(node.webDomain) ?: inheritedDomain

        val isPassword = hints.any {
            it.contains("password") || it.contains("newpassword")
        } ||
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD

        val isUsername = hints.any {
            it.contains("username") || it.contains("email") ||
                it.contains("user") || it.contains("login")
        } ||
            entry.contains("username") || entry.contains("user_name") ||
            entry.contains("email") || entry == "login" ||
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS

        val isTextInput = inputClass == InputType.TYPE_CLASS_TEXT ||
            className.endsWith("EditText", ignoreCase = true)
        val id = node.autofillId

        if (id != null) {
            val kind = when {
                isPassword -> FieldKind.PASSWORD
                isUsername -> FieldKind.USERNAME
                isTextInput -> FieldKind.OTHER_TEXT
                else -> null
            }
            if (kind != null) {
                output.add(
                    NodeField(
                        id = id,
                        kind = kind,
                        value = node.autofillValue?.textValue?.toString().orEmpty(),
                        domain = domain,
                        focused = node.isFocused,
                        resourceName = resourceName
                    )
                )
            }
        }

        for (index in 0 until node.childCount) {
            scan(node.getChildAt(index), output, domain)
        }
    }

    private fun normalizeDomain(raw: String?): String? {
        val value = raw?.trim()?.lowercase(Locale.ROOT)
            ?.removePrefix("https://")
            ?.removePrefix("http://")
            ?.removePrefix("www.")
            ?.substringBefore('/')
            ?.substringBefore(':')
            ?.trimEnd('.')
            .orEmpty()
        return value.takeIf { it.isNotBlank() && '.' in it }
    }

    private data class NodeField(
        val id: AutofillId,
        val kind: FieldKind,
        val value: String,
        val domain: String?,
        val focused: Boolean,
        val resourceName: String
    )

    private enum class FieldKind {
        USERNAME,
        PASSWORD,
        OTHER_TEXT
    }

    private companion object {
        const val MAX_SUGGESTIONS = 8
    }
}
