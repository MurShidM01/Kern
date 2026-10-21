package com.aliedu.kern.core.model

/**
 * User-safe error taxonomy. Messages here are already sanitized for display and must never
 * contain credentials, full headers, or raw response bodies (PRD 15.1 PRIV-04, 15.2).
 */
sealed class AppError(
    val userMessage: String,
    val technical: String? = null,
    val cause: Throwable? = null
) {
    class Network(userMessage: String, technical: String? = null, cause: Throwable? = null) :
        AppError(userMessage, technical, cause)

    class Timeout(userMessage: String, technical: String? = null, cause: Throwable? = null) :
        AppError(userMessage, technical, cause)

    class Auth(userMessage: String, technical: String? = null, cause: Throwable? = null) :
        AppError(userMessage, technical, cause)

    class Validation(userMessage: String, technical: String? = null, cause: Throwable? = null) :
        AppError(userMessage, technical, cause)

    class Permission(userMessage: String, technical: String? = null, cause: Throwable? = null) :
        AppError(userMessage, technical, cause)

    class NotFound(userMessage: String, technical: String? = null, cause: Throwable? = null) :
        AppError(userMessage, technical, cause)

    class Io(userMessage: String, technical: String? = null, cause: Throwable? = null) :
        AppError(userMessage, technical, cause)

    class Cancelled(userMessage: String = "Cancelled", technical: String? = null, cause: Throwable? = null) :
        AppError(userMessage, technical, cause)

    class Unknown(userMessage: String, technical: String? = null, cause: Throwable? = null) :
        AppError(userMessage, technical, cause)
}
