package dev.autopassx.autofill

import android.app.assist.AssistStructure
import android.service.autofill.*
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import dev.autopassx.security.EncryptedVault
import org.json.JSONObject

class AutoPassAutofillService : AutofillService() {
    override fun onFillRequest(request: FillRequest, cancellationSignal: android.os.CancellationSignal, callback: FillCallback) {
        try {
            val structure = request.fillContexts.lastOrNull()?.structure
            if (structure == null) { callback.onSuccess(null); return }
            val pkg = structure.activityComponent?.packageName ?: run { callback.onSuccess(null); return }
            val fields = mutableListOf<NodeField>()
            for (i in 0 until structure.windowNodeCount) scan(structure.getWindowNodeAt(i).rootViewNode, fields)
            val user = fields.firstOrNull { it.kind == "user" }
            val pass = fields.firstOrNull { it.kind == "pass" }
            if (user == null || pass == null) { callback.onSuccess(null); return }

            val matches = EncryptedVault(this).all().filter { it.optString("package") == pkg }
            val response = FillResponse.Builder()
            matches.take(8).forEach { record ->
                val username = record.optString("username")
                val password = record.optString("password")
                if (username.isNotBlank() && password.isNotBlank()) {
                    val label = record.optString("label").ifBlank { username }
                    val view = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                        setTextViewText(android.R.id.text1, "$label · $username")
                    }
                    response.addDataset(Dataset.Builder(view)
                        .setValue(user.id, AutofillValue.forText(username))
                        .setValue(pass.id, AutofillValue.forText(password))
                        .build())
                }
            }
            response.setSaveInfo(SaveInfo.Builder(
                SaveInfo.SAVE_DATA_TYPE_USERNAME or SaveInfo.SAVE_DATA_TYPE_PASSWORD,
                arrayOf(user.id, pass.id)
            ).build())
            callback.onSuccess(response.build())
        } catch (_: Exception) { callback.onSuccess(null) }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        try {
            val structure = request.fillContexts.lastOrNull()?.structure
                ?: run { callback.onFailure("No form data"); return }
            val pkg = structure.activityComponent?.packageName
                ?: run { callback.onFailure("Unknown app"); return }
            val fields = mutableListOf<NodeField>()
            for (i in 0 until structure.windowNodeCount) scan(structure.getWindowNodeAt(i).rootViewNode, fields)
            val username = fields.firstOrNull { it.kind == "user" }?.value.orEmpty()
            val password = fields.firstOrNull { it.kind == "pass" }?.value.orEmpty()
            if (username.isBlank() || password.isBlank()) {
                callback.onFailure("Username or password missing"); return
            }
            val record = JSONObject().put("package", pkg).put("label", pkg)
                .put("username", username).put("password", password)
            EncryptedVault(this).put("app:$pkg:$username", record)
            callback.onSuccess()
        } catch (_: Exception) { callback.onFailure("Unable to save account") }
    }

    private data class NodeField(val id: AutofillId, val kind: String, val value: String)

    private fun scan(node: AssistStructure.ViewNode, out: MutableList<NodeField>) {
        val hints = node.autofillHints?.toList().orEmpty()
        val input = node.inputType
        val variation = input and android.text.InputType.TYPE_MASK_VARIATION
        val password = hints.any { it.contains("password", true) } ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        val user = hints.any { it.contains("username", true) || it.contains("email", true) } ||
            node.idEntry?.contains("user", true) == true || node.idEntry?.contains("email", true) == true
        val id = node.autofillId
        if (id != null && password) out.add(NodeField(id, "pass", node.autofillValue?.textValue?.toString().orEmpty()))
        else if (id != null && user) out.add(NodeField(id, "user", node.autofillValue?.textValue?.toString().orEmpty()))
        for (i in 0 until node.childCount) scan(node.getChildAt(i), out)
    }
}