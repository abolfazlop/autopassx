package dev.autopassx.autofill

import android.app.assist.AssistStructure
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import dev.autopassx.security.EncryptedVault
import org.json.JSONObject
import java.util.Locale

class AutoPassAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: android.os.CancellationSignal,
        callback: FillCallback
    ) {
        try {
            if (cancellationSignal.isCanceled) {
                callback.onSuccess(null)
                return
            }

            val structure = request.fillContexts.lastOrNull()?.structure
            if (structure == null) {
                callback.onSuccess(null)
                return
            }

            val packageName = structure.activityComponent?.packageName
            if (packageName.isNullOrBlank() || packageName == this.packageName) {
                callback.onSuccess(null)
                return
            }

            val fields = collectFields(structure)
            val passwordField = fields.firstOrNull { it.kind == FieldKind.PASSWORD }
            if (passwordField == null) {
                callback.onSuccess(null)
                return
            }

            // Web credentials are scoped to the actual web domain, not the browser app.
            val domain = passwordField.domain
                ?: fields.firstOrNull { it.kind == FieldKind.USERNAME }?.domain

            val records = EncryptedVault(this).all().filter { record ->
                val savedDomain = normalizeDomain(record.optString("domain"))
                if (!domain.isNullOrBlank()) {
                    savedDomain == domain
                } else {
                    savedDomain.isNullOrBlank() && record.optString("package") == packageName
                }
            }

            val usernameField = fields.firstOrNull { it.kind == FieldKind.USERNAME }
                ?: fields.firstOrNull {
                    it.kind == FieldKind.OTHER_TEXT &&
                        fields.indexOf(it) < fields.indexOf(passwordField)
                }

            val response = FillResponse.Builder()
            if (usernameField != null) {
                records.take(MAX_SUGGESTIONS).forEach { record ->
                    val username = record.optString("username")
                    val password = record.optString("password")
                    if (username.isNotBlank() && password.isNotBlank()) {
                        val label = record.optString("label").ifBlank { username }
                        val presentation = RemoteViews(
                            this.packageName,
                            android.R.layout.simple_list_item_1
                        ).apply {
                            setTextViewText(android.R.id.text1, "$label · $username")
                        }
                        response.addDataset(
                            Dataset.Builder(presentation)
                                .setValue(usernameField.id, AutofillValue.forText(username))
                                .setValue(passwordField.id, AutofillValue.forText(password))
                                .build()
                        )
                    }
                }

                response.setSaveInfo(
                    SaveInfo.Builder(
                        SaveInfo.SAVE_DATA_TYPE_USERNAME or SaveInfo.SAVE_DATA_TYPE_PASSWORD,
                        arrayOf(usernameField.id, passwordField.id)
                    ).build()
                )
                callback.onSuccess(response.build())
            } else {
                callback.onSuccess(null)
            }
        } catch (_: Exception) {
            callback.onSuccess(null)
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        try {
            val structure = request.fillContexts.lastOrNull()?.structure
            if (structure == null) {
                callback.onFailure("No form data was provided")
                return
            }

            val packageName = structure.activityComponent?.packageName
            if (packageName.isNullOrBlank() || packageName == this.packageName) {
                callback.onFailure("Unable to identify the source application")
                return
            }

            val fields = collectFields(structure)
            val passwordField = fields.firstOrNull {
                it.kind == FieldKind.PASSWORD && it.value.isNotBlank()
            }
            val usernameField = fields.firstOrNull {
                it.kind == FieldKind.USERNAME && it.value.isNotBlank()
            } ?: fields.firstOrNull {
                it.kind == FieldKind.OTHER_TEXT &&
                    it.value.isNotBlank() &&
                    (passwordField == null || fields.indexOf(it) < fields.indexOf(passwordField))
            }

            val username = usernameField?.value?.trim().orEmpty()
            val password = passwordField?.value.orEmpty()
            if (username.isBlank() || password.isBlank()) {
                callback.onFailure("Username or password could not be read")
                return
            }

            val domain = normalizeDomain(
                passwordField?.domain ?: usernameField?.domain
            )
            val appLabel = if (domain != null) {
                domain
            } else {
                runCatching {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(packageName, 0)
                    ).toString()
                }.getOrDefault(packageName)
            }

            val record = JSONObject()
                .put("package", packageName)
                .put("domain", domain.orEmpty())
                .put("label", appLabel)
                .put("username", username)
                .put("password", password)

            val recordId = if (domain != null) {
                "web:$domain:$username"
            } else {
                "app:$packageName:$username"
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
        val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
        val inputClass = inputType and android.text.InputType.TYPE_MASK_CLASS
        val entry = node.idEntry.orEmpty().lowercase(Locale.ROOT)
        val className = node.className.orEmpty()
        val domain = normalizeDomain(node.webDomain) ?: inheritedDomain

        val isPassword = hints.any { it.contains("password") } ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD

        val isUsername = hints.any {
            it.contains("username") || it.contains("email") || it.contains("newusername")
        } ||
            entry.contains("username") ||
            entry.contains("user_name") ||
            entry.contains("email") ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS

        val isTextInput = inputClass == android.text.InputType.TYPE_CLASS_TEXT ||
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
                        domain = domain
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
        val domain: String?
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
