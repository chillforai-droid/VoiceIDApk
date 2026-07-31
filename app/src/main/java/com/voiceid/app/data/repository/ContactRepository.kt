package com.voiceid.app.data.repository

import com.voiceid.app.data.model.Contact
import com.voiceid.app.data.model.ContactInsert
import com.voiceid.app.data.model.Profile
import com.voiceid.app.data.remote.SupabaseModule
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order

/** Implements API_REFERENCE.md §3.5 exactly — do not add client-side notification inserts. */
class ContactRepository {

    private val client = SupabaseModule.client()

    suspend fun sendFriendRequest(responderId: String) {
        val userId = SupabaseModule.currentUserId() ?: throw AuthException("Not authenticated")
        client.from("contacts").insert(ContactInsert(requesterId = userId, responderId = responderId))
    }

    suspend fun acceptRequest(contactId: String) {
        client.from("contacts").update({ set("status", "accepted") }) {
            filter { eq("id", contactId) }
        }
    }

    suspend fun declineOrBlock(contactId: String) {
        client.from("contacts").update({ set("status", "blocked") }) {
            filter { eq("id", contactId) }
        }
    }

    suspend fun removeContact(contactId: String) {
        client.from("contacts").delete { filter { eq("id", contactId) } }
    }

    /** SELECT * FROM contacts WHERE requester_id = auth.uid() OR responder_id = auth.uid() */
    suspend fun myContacts(): List<Contact> {
        val userId = SupabaseModule.currentUserId() ?: return emptyList()
        return client.from("contacts").select {
            filter {
                or {
                    eq("requester_id", userId)
                    eq("responder_id", userId)
                }
            }
            order("created_at", Order.DESCENDING)
        }.decodeList()
    }

    suspend fun profileById(userId: String): Profile? =
        client.from("profiles").select { filter { eq("id", userId) } }.decodeSingleOrNull()

    suspend fun profilesByIds(ids: List<String>): List<Profile> {
        if (ids.isEmpty()) return emptyList()
        return client.from("profiles").select {
            filter { isIn("id", ids) }
        }.decodeList()
    }

    /** SELECT * FROM profiles WHERE username ILIKE '%q%' OR display_name ILIKE '%q%' LIMIT 10 — API_REFERENCE.md §3.9 */
    suspend fun searchUsers(query: String): List<Profile> {
        if (query.isBlank()) return emptyList()
        return client.from("profiles").select {
            filter {
                or {
                    ilike("username", "%$query%")
                    ilike("display_name", "%$query%")
                }
            }
            limit(10)
        }.decodeList()
    }
}
