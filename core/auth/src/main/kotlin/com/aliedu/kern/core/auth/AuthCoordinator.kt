package com.aliedu.kern.core.auth

import com.aliedu.kern.core.data.security.CredentialStore
import com.aliedu.kern.core.model.AuthMethod
import com.aliedu.kern.core.model.ProviderProtocol
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Authentication capability contract (PRD 10.3). Part 1 ships API-key credentials only;
 * browser/device-code flows are declared unsupported until officially verified in Part 4.
 */
interface AuthCoordinator {
    fun supportedMethods(protocol: ProviderProtocol): List<AuthMethod>
    fun isMethodSupported(protocol: ProviderProtocol, method: AuthMethod): Boolean
    suspend fun hasCredential(profileId: String): Boolean
    suspend fun deleteCredential(profileId: String)
}

@Singleton
class DefaultAuthCoordinator @Inject constructor(
    private val credentialStore: CredentialStore
) : AuthCoordinator {

    override fun supportedMethods(protocol: ProviderProtocol): List<AuthMethod> =
        listOf(AuthMethod.API_KEY)

    override fun isMethodSupported(protocol: ProviderProtocol, method: AuthMethod): Boolean =
        method == AuthMethod.API_KEY

    override suspend fun hasCredential(profileId: String): Boolean =
        credentialStore.hasCredential(profileId)

    override suspend fun deleteCredential(profileId: String) =
        credentialStore.delete(profileId)
}
