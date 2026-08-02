package com.voiceid.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors the `profiles` table exactly (BACKEND_README.md §5.1). */
@Serializable
data class Profile(
    @SerialName("id") val id: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("username") val username: String? = null,
    @SerialName("bio") val bio: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("country") val country: String? = null,
    @SerialName("language") val language: String? = null,
    @SerialName("timezone") val timezone: String? = null,
    @SerialName("is_verified") val isVerified: Boolean = false,
    @SerialName("is_business") val isBusiness: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

/** Mirrors the `contacts` table. status: pending | accepted | blocked */
@Serializable
data class Contact(
    @SerialName("id") val id: String,
    @SerialName("requester_id") val requesterId: String,
    @SerialName("responder_id") val responderId: String,
    @SerialName("status") val status: String,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class ContactInsert(
    @SerialName("requester_id") val requesterId: String,
    @SerialName("responder_id") val responderId: String,
    @SerialName("status") val status: String = "pending"
)

/** Mirrors `conversations` table. */
@Serializable
data class Conversation(
    @SerialName("id") val id: String,
    @SerialName("is_group") val isGroup: Boolean = false,
    @SerialName("last_message_at") val lastMessageAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class ConversationMember(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("user_id") val userId: String
)

/** Mirrors `messages` table, all content_type variants. */
@Serializable
data class Message(
    @SerialName("id") val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("content_type") val contentType: String, // "text" | "voice" | "image"
    @SerialName("content_body") val contentBody: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("storage_path") val storagePath: String? = null,
    @SerialName("duration") val duration: Int? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("b2_object_key") val b2ObjectKey: String? = null,
    @SerialName("sha256") val sha256: String? = null,
    @SerialName("media_status") val mediaStatus: String? = null, // pending | delivered
    @SerialName("byte_size") val byteSize: Long? = null
)

/** Mirrors `calls` table. */
@Serializable
data class Call(
    @SerialName("id") val id: String,
    @SerialName("caller_id") val callerId: String,
    @SerialName("receiver_id") val receiverId: String,
    @SerialName("status") val status: String, // ringing|accepted|rejected|ended|missed|cancelled|failed
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("answered_at") val answeredAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null
)

/** Mirrors `notifications` table. */
@Serializable
data class AppNotification(
    @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("actor_id") val actorId: String? = null,
    @SerialName("title") val title: String,
    @SerialName("message") val message: String,
    @SerialName("type") val type: String, // message|friend_request|friend_accepted|missed_call
    @SerialName("related_id") val relatedId: String? = null,
    @SerialName("secondary_id") val secondaryId: String? = null,
    @SerialName("is_read") val isRead: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null
)

/** Mirrors `user_settings` table. */
@Serializable
data class UserSettings(
    @SerialName("user_id") val userId: String,
    @SerialName("contact_requests") val contactRequests: String = "everyone",
    @SerialName("calls") val calls: String = "everyone",
    @SerialName("voice_messages") val voiceMessages: String = "everyone",
    @SerialName("notify_contact_requests") val notifyContactRequests: Boolean = true,
    @SerialName("notify_messages") val notifyMessages: Boolean = true,
    @SerialName("notify_calls") val notifyCalls: Boolean = true
)

/** Relationship between the current user and a searched profile, derived from `contacts.status`. */
enum class FriendStatus { NONE, PENDING, FRIENDS }

/** Convenience UI-layer projection: a searched profile joined with friend status + presence. */
data class SearchResult(
    val profile: Profile,
    val friendStatus: FriendStatus,
    val isOnline: Boolean
)

/** Convenience UI-layer projection: a conversation joined with the other participant + last message preview. */
data class ConversationSummary(
    val conversation: Conversation,
    val otherUser: Profile,
    val lastMessagePreview: String,
    val lastMessageAt: String?,
    val isOnline: Boolean,
    val unreadCount: Int
)

/**
 * BUG FIX (2026-08-02): MessageRepository previously built these three insert payloads with a
 * plain `mapOf(...)` mixing String/Int/Long values, which Kotlin infers as `Map<String, Any>`.
 * kotlinx.serialization (which Postgrest-kt uses to encode the insert body) has no serializer
 * for `Any` — there's no way to know at compile time what's actually inside it — so every
 * text/voice/image send crashed with "Serializer for class 'Any' is not found." These
 * @Serializable data classes give every field a concrete, known type instead.
 */
@Serializable
data class TextMessageInsert(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("content_body") val contentBody: String,
    @SerialName("content_type") val contentType: String = "text"
)

@Serializable
data class VoiceMessageInsert(
    @SerialName("id") val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("content_type") val contentType: String = "voice",
    @SerialName("b2_object_key") val b2ObjectKey: String,
    @SerialName("sha256") val sha256: String,
    @SerialName("media_status") val mediaStatus: String = "pending",
    @SerialName("duration") val duration: Int,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("byte_size") val byteSize: Long
)

@Serializable
data class ImageMessageInsert(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("content_type") val contentType: String = "image",
    @SerialName("b2_object_key") val b2ObjectKey: String,
    @SerialName("sha256") val sha256: String,
    @SerialName("media_status") val mediaStatus: String = "delivered",
    @SerialName("mime_type") val mimeType: String,
    @SerialName("byte_size") val byteSize: Long
)
