package ai.openclaw.app.gateway

import android.annotation.SuppressLint
import android.net.http.X509TrustManagerExtensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class GatewayTlsParams(
  val required: Boolean,
  val expectedFingerprint: String?,
  val allowTOFU: Boolean,
  val stableId: String,
)

data class GatewayTlsConfig(
  val sslSocketFactory: SSLSocketFactory,
  val trustManager: X509TrustManager,
  val hostnameVerifier: HostnameVerifier,
)

fun buildGatewayTlsConfig(
  params: GatewayTlsParams?,
  onStore: ((String) -> Unit)? = null,
): GatewayTlsConfig? {
  if (params == null) return null
  val expected = parseGatewayFingerprint(params.expectedFingerprint)
  val defaultTrust = defaultTrustManager()
  val trustExtensions = X509TrustManagerExtensions(defaultTrust)
  @SuppressLint("CustomX509TrustManager")
  val trustManager =
    object : X509TrustManager {
      override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        defaultTrust.checkClientTrusted(chain, authType)
      }

      override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("empty certificate chain")
        val fingerprint = sha256Hex(chain[0].encoded)
        if (expected != null) {
          if (fingerprint != expected) {
            throw CertificateException("gateway TLS fingerprint mismatch")
          }
          return
        }
        if (params.allowTOFU) {
          onStore?.invoke(fingerprint)
          return
        }
        defaultTrust.checkServerTrusted(chain, authType)
      }

      // Android/OkHttp reflectively looks for this hostname-aware overload.
      @Suppress("unused")
      fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        host: String,
      ): List<X509Certificate> {
        if (chain.isEmpty()) throw CertificateException("empty certificate chain")
        val fingerprint = sha256Hex(chain[0].encoded)
        if (expected != null) {
          if (fingerprint != expected) {
            throw CertificateException("gateway TLS fingerprint mismatch")
          }
          return chain.toList()
        }
        if (params.allowTOFU) {
          onStore?.invoke(fingerprint)
          return chain.toList()
        }
        return trustExtensions.checkServerTrusted(chain, authType, host)
      }

      override fun getAcceptedIssuers(): Array<X509Certificate> = defaultTrust.acceptedIssuers
    }

  val context = SSLContext.getInstance("TLS")
  context.init(null, arrayOf(trustManager), SecureRandom())
  val verifier =
    if (expected != null || params.allowTOFU) {
      // When pinning, we intentionally ignore hostname mismatch (service discovery often yields IPs).
      HostnameVerifier { _, _ -> true }
    } else {
      HttpsURLConnection.getDefaultHostnameVerifier()
    }
  return GatewayTlsConfig(
    sslSocketFactory = context.socketFactory,
    trustManager = trustManager,
    hostnameVerifier = verifier,
  )
}

suspend fun probeGatewayTlsFingerprint(
  host: String,
  port: Int,
  timeoutMs: Int = 3_000,
): String? {
  val trimmedHost = host.trim()
  if (trimmedHost.isEmpty()) return null
  if (port !in 1..65535) return null

  return withContext(Dispatchers.IO) {
    val trustAll =
      @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
      object : X509TrustManager {
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
      }

    val context = SSLContext.getInstance("TLS")
    context.init(null, arrayOf(trustAll), SecureRandom())
    probeGatewayTlsFingerprintOnce(
      context = context,
      host = trimmedHost,
      port = port,
      timeoutMs = timeoutMs,
      useDirectHostSocket = false,
    ) ?: probeGatewayTlsFingerprintOnce(
      context = context,
      host = trimmedHost,
      port = port,
      timeoutMs = timeoutMs,
      useDirectHostSocket = true,
    )
  }
}

suspend fun isGatewayTlsSystemTrusted(
  host: String,
  port: Int,
  timeoutMs: Int = 3_000,
): Boolean {
  val trimmedHost = host.trim()
  if (trimmedHost.isEmpty()) return false
  if (port !in 1..65535) return false

  return withContext(Dispatchers.IO) {
    val context = SSLContext.getInstance("TLS")
    val defaultTrust = defaultTrustManager()
    context.init(null, arrayOf<TrustManager>(defaultTrust), SecureRandom())
    probeGatewayTlsSystemTrustOnce(
      context = context,
      host = trimmedHost,
      port = port,
      timeoutMs = timeoutMs,
      useDirectHostSocket = false,
    ) || probeGatewayTlsSystemTrustOnce(
      context = context,
      host = trimmedHost,
      port = port,
      timeoutMs = timeoutMs,
      useDirectHostSocket = true,
    )
  }
}

private fun defaultTrustManager(): X509TrustManager {
  val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
  factory.init(null as java.security.KeyStore?)
  val trust =
    factory.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
  return trust ?: throw IllegalStateException("No default X509TrustManager found")
}

private fun sha256Hex(data: ByteArray): String {
  val digest = MessageDigest.getInstance("SHA-256").digest(data)
  val out = StringBuilder(digest.size * 2)
  for (byte in digest) {
    out.append(String.format(Locale.US, "%02x", byte))
  }
  return out.toString()
}

internal fun normalizeGatewayFingerprint(raw: String): String {
  val stripped = raw.trim()
    .replace(Regex("^sha-?256\\s*:?\\s*", RegexOption.IGNORE_CASE), "")
  return stripped.lowercase(Locale.US).filter { it in '0'..'9' || it in 'a'..'f' }
}

internal fun parseGatewayFingerprint(raw: String?): String? {
  val normalized = raw?.takeIf { it.isNotBlank() }?.let(::normalizeGatewayFingerprint) ?: return null
  return normalized.takeIf { it.length == 64 }
}

internal fun isSystemTlsTrustCandidateHost(host: String): Boolean {
  val trimmed = host.trim().trim('[', ']')
  if (trimmed.isEmpty()) return false
  if (trimmed.equals("localhost", ignoreCase = true)) return false
  if (trimmed.endsWith(".local", ignoreCase = true)) return false
  if (!trimmed.contains('.')) return false
  return !isIpLiteralHost(trimmed)
}

private fun probeGatewayTlsFingerprintOnce(
  context: SSLContext,
  host: String,
  port: Int,
  timeoutMs: Int,
  useDirectHostSocket: Boolean,
): String? {
  val socket =
    try {
      if (useDirectHostSocket) {
        context.socketFactory.createSocket(host, port) as SSLSocket
      } else {
        (context.socketFactory.createSocket() as SSLSocket).apply {
          connect(InetSocketAddress(host, port), timeoutMs)
        }
      }
    } catch (_: Throwable) {
      return null
    }

  try {
    socket.soTimeout = timeoutMs

    // Best-effort SNI for hostnames (avoid crashing on IP literals).
    try {
      if (host.any { it.isLetter() }) {
        val params = SSLParameters()
        params.serverNames = listOf(SNIHostName(host))
        socket.sslParameters = params
      }
    } catch (_: Throwable) {
      // ignore
    }

    socket.startHandshake()
    val cert = socket.session.peerCertificates.firstOrNull() as? X509Certificate ?: return null
    return sha256Hex(cert.encoded)
  } catch (_: Throwable) {
    return null
  } finally {
    try {
      socket.close()
    } catch (_: Throwable) {
      // ignore
    }
  }
}

private fun probeGatewayTlsSystemTrustOnce(
  context: SSLContext,
  host: String,
  port: Int,
  timeoutMs: Int,
  useDirectHostSocket: Boolean,
): Boolean {
  val socket =
    try {
      if (useDirectHostSocket) {
        context.socketFactory.createSocket(host, port) as SSLSocket
      } else {
        (context.socketFactory.createSocket() as SSLSocket).apply {
          connect(InetSocketAddress(host, port), timeoutMs)
        }
      }
    } catch (_: Throwable) {
      return false
    }

  try {
    socket.soTimeout = timeoutMs

    val params = SSLParameters().apply {
      endpointIdentificationAlgorithm = "HTTPS"
    }
    try {
      if (isHostnameForSni(host)) {
        params.serverNames = listOf(SNIHostName(host))
      }
    } catch (_: Throwable) {
      // ignore
    }
    socket.sslParameters = params

    socket.startHandshake()
    return true
  } catch (_: Throwable) {
    return false
  } finally {
    try {
      socket.close()
    } catch (_: Throwable) {
      // ignore
    }
  }
}

private fun isHostnameForSni(host: String): Boolean {
  val trimmed = host.trim().trim('[', ']')
  if (trimmed.isEmpty()) return false
  return !isIpLiteralHost(trimmed)
}

private fun isIpLiteralHost(host: String): Boolean {
  if (':' in host) return true
  if (host.count { it == '.' } == 3 && host.all { it.isDigit() || it == '.' }) return true
  return false
}
