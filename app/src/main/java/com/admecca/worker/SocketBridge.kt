package com.admecca.worker

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Root로 실행되는 TCP→Chrome DevTools 추상 소켓 브릿지.
 * 앱에서 su -c "CLASSPATH=<apk> app_process / com.admecca.worker.SocketBridge" 로 실행.
 */
object SocketBridge {

    private const val CHROME_SOCKET = "chrome_devtools_remote"
    private const val BUFFER_SIZE = 16384

    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.firstOrNull()?.toIntOrNull() ?: 9222
        val server = ServerSocket(port)
        server.reuseAddress = true
        System.out.println("CDP-Bridge listening on :$port")
        System.out.flush()

        while (true) {
            try {
                val tcp = server.accept()
                Thread { handleConnection(tcp) }.start()
            } catch (e: Exception) {
                System.err.println("accept error: ${e.message}")
            }
        }
    }

    private fun handleConnection(tcp: Socket) {
        var local: LocalSocket? = null
        try {
            local = LocalSocket()
            local.connect(
                LocalSocketAddress(CHROME_SOCKET, LocalSocketAddress.Namespace.ABSTRACT)
            )

            val t1 = Thread { pipe(tcp.getInputStream(), local!!.outputStream) }
            val t2 = Thread { pipe(local!!.inputStream, tcp.getOutputStream()) }
            t1.isDaemon = true
            t2.isDaemon = true
            t1.start()
            t2.start()
            t1.join()
            t2.join()
        } catch (e: Exception) {
            System.err.println("bridge error: ${e.message}")
        } finally {
            try { local?.close() } catch (_: Exception) {}
            try { tcp.close() } catch (_: Exception) {}
        }
    }

    private fun pipe(src: InputStream, dst: OutputStream) {
        try {
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                dst.write(buf, 0, n)
                dst.flush()
            }
        } catch (_: Exception) {}
    }
}
