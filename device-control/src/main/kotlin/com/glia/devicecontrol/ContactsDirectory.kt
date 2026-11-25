package com.glia.devicecontrol

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** Busca contatos por nome (telefone). Só leitura. */
class ContactsDirectory(private val context: Context) {

    data class Contact(val name: String, val number: String)

    fun canRead(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED

    fun findByNumberOrName(query: String): Contact? {
        if (!canRead()) return null
        val q = query.trim()
        if (q.isBlank()) return null
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
            "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
        val args = arrayOf("%$q%", "%${q.filter { it.isDigit() }}%")
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                args,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0) ?: q
                    val number = cursor.getString(1) ?: return@use
                    return Contact(name, number.replace(" ", ""))
                }
            }
        }
        if (q.any { it.isDigit() } && q.length >= 3) return Contact(q, q.filter { it.isDigit() })
        return null
    }
}
