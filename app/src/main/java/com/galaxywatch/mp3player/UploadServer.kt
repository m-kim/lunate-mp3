package com.galaxywatch.mp3player

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.security.MessageDigest

class UploadServer(
    context: Context,
    private val accessCode: String,
    private val onUploadComplete: (File) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val musicDirectory = File(context.filesDir, "music").apply { mkdirs() }
    private val libraryRepository = LibraryRepository(context)
    private val running = AtomicBoolean(false)
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientExecutor = Executors.newFixedThreadPool(2)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private var serverSocket: ServerSocket? = null

    var port: Int = 0
        private set

    fun start(): String? {
        if (!running.compareAndSet(false, true)) return address()

        return try {
            serverSocket = ServerSocket(0).also { socket ->
                socket.reuseAddress = true
                port = socket.localPort
            }
            acceptExecutor.execute(::acceptConnections)
            address()
        } catch (error: Exception) {
            running.set(false)
            onError(error.message ?: "Could not start the upload server")
            null
        }
    }

    private fun acceptConnections() {
        while (running.get()) {
            try {
                val client = serverSocket?.accept() ?: break
                client.soTimeout = SOCKET_TIMEOUT_MS
                clients.add(client)
                clientExecutor.execute { handleClient(client) }
            } catch (error: Exception) {
                if (running.get()) onError(error.message ?: "Transfer connection failed")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { client ->
                try {
                    val input = BufferedInputStream(client.getInputStream())
                    val requestLine = readHttpLine(input)
                    val requestParts = requestLine.split(' ', limit = 3)
                    if (requestParts.size < 2) {
                        send(client, 400, "Bad Request", "Invalid request")
                        return
                    }

                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = readHttpLine(input)
                        if (line.isEmpty()) break
                        val separator = line.indexOf(':')
                        if (separator > 0) {
                            headers[line.substring(0, separator).lowercase(Locale.US)] =
                                line.substring(separator + 1).trim()
                        }
                    }

                    when {
                        requestParts[0] == "GET" && requestParts[1].substringBefore('?') == "/" -> {
                            send(client, 200, "OK", uploadPage(), "text/html; charset=utf-8")
                        }
                        requestParts[0] == "GET" && requestParts[1].substringBefore('?') == "/storage" -> {
                            send(client, 200, "OK", storageJson(), "application/json; charset=utf-8")
                        }
                        requestParts[0] == "GET" && requestParts[1].substringBefore('?') == "/artists" -> {
                            send(client, 200, "OK", artistsJson(), "application/json; charset=utf-8")
                        }
                        requestParts[0] == "POST" && requestParts[1].substringBefore('?') == "/artist-alias" -> {
                            saveArtistAlias(client, requestParts[1])
                        }
                        requestParts[0] == "POST" && requestParts[1].substringBefore('?') == "/upload" -> {
                            receiveUpload(client, input, requestParts[1], headers)
                        }
                        else -> send(client, 404, "Not Found", "Not found")
                    }
                } catch (error: Exception) {
                    runCatching { send(client, 500, "Server Error", error.message ?: "Upload failed") }
                    if (running.get()) onError(error.message ?: "Upload failed")
                }
            }
        } finally {
            clients.remove(socket)
        }
    }

    @Synchronized
    private fun receiveUpload(
        client: Socket,
        input: BufferedInputStream,
        target: String,
        headers: Map<String, String>,
    ) {
        val query = parseQuery(target.substringAfter('?', ""))
        if (query["code"] != accessCode) {
            send(client, 403, "Forbidden", "Incorrect access code")
            return
        }

        val originalName = query["name"].orEmpty()
        if (!SupportedAudio.hasSupportedExtension(originalName)) {
            send(
                client,
                415,
                "Unsupported Media Type",
                "Rejected ${originalName.ifBlank { "unnamed file" }}: only .mp3 and .m4a files are accepted",
            )
            return
        }

        val safeName = UploadRules.sanitizeFileName(originalName)
        if (safeName.isBlank() || !SupportedAudio.hasSupportedExtension(safeName)) {
            send(client, 415, "Unsupported Media Type", "Rejected $originalName: invalid filename")
            return
        }

        val contentLength = headers["content-length"]?.toLongOrNull()
        if (contentLength == null || contentLength <= 0L) {
            send(client, 411, "Length Required", "Missing file size")
            return
        }
        if (contentLength > MAX_FILE_BYTES) {
            send(client, 413, "Content Too Large", "File exceeds the 1 GB limit")
            return
        }
        val availableForMusic = StorageStatus.read(musicDirectory).availableForMusicBytes
        if (availableForMusic < contentLength) {
            send(
                client,
                507,
                "Insufficient Storage",
                "Not enough free space: ${formatBytes(availableForMusic)} available for music",
            )
            return
        }

        val destination = uniqueDestination(safeName)
        val temporary = File(musicDirectory, ".${destination.name}.uploading")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var remaining = contentLength
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) throw IllegalStateException("Upload ended before the file was complete")
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    remaining -= count
                }
                output.fd.sync()
            }
            val contentHash = digest.digest()
            val duplicate = DuplicateFiles.findMatch(musicDirectory, temporary, contentHash)
            if (duplicate != null) {
                send(
                    client,
                    409,
                    "Conflict",
                    "Skipped ${destination.name}: identical to ${duplicate.name}",
                )
                return
            }
            if (!temporary.renameTo(destination)) {
                throw IllegalStateException("Could not finish saving the file")
            }
            libraryRepository.indexUploaded(destination, contentHash)
            onUploadComplete(destination)
            send(client, 201, "Created", "Saved ${destination.name}")
        } finally {
            temporary.delete()
        }
    }

    private fun uniqueDestination(fileName: String): File {
        var candidate = File(musicDirectory, fileName)
        if (!candidate.exists()) return candidate

        val base = fileName.substringBeforeLast('.')
        val extension = fileName.substringAfterLast('.', "mp3")
        var index = 2
        while (candidate.exists()) {
            candidate = File(musicDirectory, "$base ($index).$extension")
            index++
        }
        return candidate
    }

    private fun uploadPage(): String = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width,initial-scale=1">
          <title>Wrist MP3 Transfer</title>
          <style>
            body{font:16px system-ui,sans-serif;background:#101820;color:#eefbff;max-width:700px;margin:0 auto;padding:32px}
            h1{color:#6ee7ff}.drop{border:2px dashed #6ee7ff;border-radius:20px;padding:48px 20px;text-align:center;cursor:pointer}
            .drop.over{background:#17323c}#picker{display:none}#code{font:inherit;font-size:20px;letter-spacing:4px;width:9em;padding:8px;border-radius:8px;border:1px solid #6ee7ff;background:#17323c;color:white}
            #status{list-style:none;padding:0}li{margin:10px 0;padding:12px;border-radius:10px;white-space:pre-line}.ok{color:#7df5a5;background:#102a20}.bad{color:#ffb0b0;background:#32191d}
            .storage{background:#17323c;border-radius:12px;padding:14px;margin:18px 0}.meter{height:8px;background:#31434b;border-radius:8px;overflow:hidden;margin:9px 0}.meter span{display:block;height:100%;background:#6ee7ff;width:0}.storage small{color:#b9cbd1}
            .cleanup{margin-top:28px;padding-top:16px;border-top:1px solid #31434b}.cleanup select,.cleanup input,.cleanup button{box-sizing:border-box;width:100%;font:inherit;margin:5px 0;padding:10px;border-radius:8px;border:1px solid #526973;background:#17323c;color:white}.cleanup button{border-color:#6ee7ff;color:#6ee7ff;cursor:pointer}.cleanup-result{min-height:1.4em;color:#7df5a5}
          </style>
        </head>
        <body>
          <h1>Wrist MP3</h1>
          <p>Keep this page open and leave Transfer mode open on the watch.</p>
          <section class="storage">
            <strong id="space">Checking storage…</strong>
            <div class="meter"><span id="storageBar"></span></div>
            <small id="storageDetail"></small>
          </section>
          <p><label>Access code<br><input id="code" inputmode="numeric" maxlength="6" autocomplete="one-time-code"></label></p>
          <div id="drop" class="drop">Drop MP3 or M4A files here<br><small>or click to choose files</small></div>
          <input id="picker" type="file" accept="audio/mpeg,audio/mp4,audio/x-m4a,.mp3,.m4a" multiple>
          <ul id="status"></ul>
          <section class="cleanup">
            <h2>Artist cleanup</h2>
            <p>Merge an incorrect artist spelling into the name you want displayed. Audio files are not changed.</p>
            <label>Artist to correct<select id="aliasFrom"><option>Loading artists…</option></select></label>
            <label>Display as<input id="aliasTo" maxlength="160" placeholder="Correct artist name"></label>
            <button id="saveAlias" type="button">Save artist correction</button>
            <div id="cleanupResult" class="cleanup-result"></div>
          </section>
          <script>
            const drop = document.querySelector('#drop');
            const picker = document.querySelector('#picker');
            const codeInput = document.querySelector('#code');
            const statusList = document.querySelector('#status');
            const space = document.querySelector('#space');
            const storageBar = document.querySelector('#storageBar');
            const storageDetail = document.querySelector('#storageDetail');
            const aliasFrom = document.querySelector('#aliasFrom');
            const aliasTo = document.querySelector('#aliasTo');
            const saveAlias = document.querySelector('#saveAlias');
            const cleanupResult = document.querySelector('#cleanupResult');
            drop.onclick = () => picker.click();
            drop.ondragover = e => { e.preventDefault(); drop.classList.add('over'); };
            drop.ondragleave = () => drop.classList.remove('over');
            drop.ondrop = async e => {
              e.preventDefault();
              drop.classList.remove('over');
              const entries = [...e.dataTransfer.items]
                .map(item => item.webkitGetAsEntry?.())
                .filter(Boolean);
              const files = entries.length
                ? (await Promise.all(entries.map(entry => filesIn(entry)))).flat()
                : [...e.dataTransfer.files].map(file => ({file, label: file.name}));
              if (!files.length) addStatus('No files found in the dropped selection.', false);
              await upload(files);
            };
            picker.onchange = () => upload(picker.files);
            function addStatus(message, ok) {
              const li = document.createElement('li');
              li.textContent = message;
              li.className = ok ? 'ok' : 'bad';
              statusList.appendChild(li);
              return li;
            }
            function fallbackReason(status) {
              return ({
                403: 'The access code is incorrect or has expired.',
                409: 'An identical file is already stored on the watch.',
                411: 'The browser did not provide the file size.',
                413: 'The file exceeds the 1 GB upload limit.',
                415: 'The file is not a supported MP3 or M4A audio file.',
                507: 'The watch does not have enough free storage.',
              })[status] ?? `The watch returned HTTP ${'$'}{status}.`;
            }
            function showRejected(li, label, reason) {
              li.textContent = `✗ Not uploaded: ${'$'}{label}\nReason: ${'$'}{reason}`;
              li.className = 'bad';
            }
            function formatBytes(bytes) {
              if (bytes < 1024) return `${'$'}{bytes} B`;
              const units = ['KB', 'MB', 'GB', 'TB'];
              let value = bytes / 1024;
              let unit = units[0];
              for (let i = 1; i < units.length && value >= 1024; i++) {
                value /= 1024;
                unit = units[i];
              }
              return `${'$'}{value >= 10 ? value.toFixed(1) : value.toFixed(2)} ${'$'}{unit}`;
            }
            async function updateStorage() {
              try {
                const response = await fetch('/storage', {cache: 'no-store'});
                const storage = await response.json();
                space.textContent = `${'$'}{formatBytes(storage.availableForMusicBytes)} available for music`;
                storageDetail.textContent = `Wrist MP3 uses ${'$'}{formatBytes(storage.musicBytes)} · ${'$'}{formatBytes(storage.reserveBytes)} safety reserve`;
                const used = storage.totalBytes > 0
                  ? 100 * (storage.totalBytes - storage.availableBytes) / storage.totalBytes
                  : 0;
                storageBar.style.width = `${'$'}{Math.max(0, Math.min(100, used))}%`;
              } catch (_) {
                space.textContent = 'Storage status unavailable';
              }
            }
            async function updateArtists() {
              try {
                const response = await fetch('/artists', {cache: 'no-store'});
                const artists = await response.json();
                aliasFrom.textContent = '';
                for (const artist of artists) {
                  const option = document.createElement('option');
                  option.value = artist.name;
                  option.textContent = `${'$'}{artist.name} (${'$'}{artist.count})`;
                  aliasFrom.appendChild(option);
                }
                if (!artists.length) {
                  const option = document.createElement('option');
                  option.textContent = 'No artists found';
                  aliasFrom.appendChild(option);
                }
              } catch (_) {
                aliasFrom.innerHTML = '<option>Artist list unavailable</option>';
              }
            }
            aliasFrom.onchange = () => { aliasTo.value = aliasFrom.value; };
            saveAlias.onclick = async () => {
              cleanupResult.textContent = 'Saving…';
              const response = await fetch(
                `/artist-alias?code=${'$'}{encodeURIComponent(codeInput.value)}&from=${'$'}{encodeURIComponent(aliasFrom.value)}&to=${'$'}{encodeURIComponent(aliasTo.value)}`,
                {method: 'POST'},
              );
              const message = (await response.text()).trim();
              cleanupResult.textContent = response.ok ? `✓ ${'$'}{message}` : `✗ ${'$'}{message || fallbackReason(response.status)}`;
              cleanupResult.style.color = response.ok ? '#7df5a5' : '#ffb0b0';
              if (response.ok) await updateArtists();
            };
            async function directoryEntries(reader) {
              const entries = [];
              while (true) {
                const batch = await new Promise((resolve, reject) => reader.readEntries(resolve, reject));
                if (!batch.length) return entries;
                entries.push(...batch);
              }
            }
            async function filesIn(entry, parent = '') {
              const relativeName = parent ? `${'$'}{parent}/${'$'}{entry.name}` : entry.name;
              if (entry.isFile) {
                const file = await new Promise((resolve, reject) => entry.file(resolve, reject));
                return [{file, label: relativeName}];
              }
              if (!entry.isDirectory) return [];
              const children = await directoryEntries(entry.createReader());
              return (await Promise.all(children.map(child => filesIn(child, relativeName)))).flat();
            }
            async function upload(selectedFiles) {
              for (const selected of selectedFiles) {
                const file = selected.file ?? selected;
                const label = selected.label ?? file.webkitRelativePath ?? file.name;
                const li = document.createElement('li');
                statusList.appendChild(li);
                const extension = file.name.toLowerCase().split('.').pop();
                if (!['mp3', 'm4a'].includes(extension)) {
                  showRejected(li, label, 'The filename does not end in .mp3 or .m4a.');
                  continue;
                }
                li.textContent = `Uploading ${'$'}{label}...`;
                try {
                  const response = await fetch(`/upload?code=${'$'}{encodeURIComponent(codeInput.value)}&name=${'$'}{encodeURIComponent(file.name)}`, {
                    method: 'POST',
                    headers: {'Content-Type': extension === 'm4a' ? 'audio/mp4' : 'audio/mpeg'},
                    body: file
                  });
                  const serverMessage = (await response.text()).trim();
                  if (response.ok) {
                    li.textContent = `✓ Uploaded: ${'$'}{label}\n${'$'}{serverMessage || 'Saved on watch'}`;
                    li.className = 'ok';
                  } else {
                    showRejected(li, label, serverMessage || fallbackReason(response.status));
                  }
                } catch (error) {
                  showRejected(
                    li,
                    label,
                    'Connection lost. Keep Transfer mode open and confirm both devices are on the same Wi-Fi.',
                  );
                }
                await updateStorage();
              }
            }
            updateStorage();
            updateArtists();
          </script>
        </body>
        </html>
    """.trimIndent()

    private fun storageJson(): String {
        val storage = StorageStatus.read(musicDirectory)
        return """{"totalBytes":${storage.totalBytes},"availableBytes":${storage.availableBytes},"availableForMusicBytes":${storage.availableForMusicBytes},"musicBytes":${storage.musicBytes},"reserveBytes":${storage.reserveBytes}}"""
    }

    private fun artistsJson(): String = libraryRepository.artistNames().joinToString(
        prefix = "[",
        postfix = "]",
    ) { (name, count) -> "{\"name\":${jsonString(name)},\"count\":$count}" }

    private fun saveArtistAlias(client: Socket, target: String) {
        val query = parseQuery(target.substringAfter('?', ""))
        if (query["code"] != accessCode) {
            send(client, 403, "Forbidden", "Incorrect access code")
            return
        }
        val alias = query["from"].orEmpty()
        val canonical = query["to"].orEmpty()
        runCatching { libraryRepository.saveArtistAlias(alias, canonical) }
            .onSuccess {
                send(client, 200, "OK", "$alias will display as ${ArtistNames.display(canonical)}")
            }
            .onFailure { error ->
                send(client, 400, "Bad Request", error.message ?: "Could not save artist correction")
            }
    }

    private fun address(): String? {
        val ip = localIpv4Address() ?: return null
        return "http://$ip:$port"
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        clients.forEach { client -> runCatching { client.close() } }
        clients.clear()
        acceptExecutor.shutdownNow()
        clientExecutor.shutdownNow()
    }

    companion object {
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val MAX_HEADER_LINE_BYTES = 8 * 1024
        private const val MAX_FILE_BYTES = 1024L * 1024L * 1024L

        private fun formatBytes(bytes: Long): String {
            val megabytes = bytes.toDouble() / (1024.0 * 1024.0)
            return if (megabytes >= 1024.0) {
                "%.1f GB".format(Locale.US, megabytes / 1024.0)
            } else {
                "%.0f MB".format(Locale.US, megabytes)
            }
        }

        private fun jsonString(value: String): String = buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character.code < 0x20) {
                        append("\\u%04x".format(character.code))
                    } else {
                        append(character)
                    }
                }
            }
            append('"')
        }

        private fun readHttpLine(input: BufferedInputStream): String {
            val bytes = ArrayList<Byte>()
            while (bytes.size < MAX_HEADER_LINE_BYTES) {
                val value = input.read()
                if (value < 0) throw IllegalStateException("Connection closed unexpectedly")
                if (value == '\n'.code) break
                if (value != '\r'.code) bytes.add(value.toByte())
            }
            if (bytes.size >= MAX_HEADER_LINE_BYTES) throw IllegalStateException("Request header is too large")
            return bytes.toByteArray().toString(StandardCharsets.UTF_8)
        }

        private fun parseQuery(query: String): Map<String, String> = query
            .split('&')
            .filter { it.isNotBlank() }
            .associate { part ->
                val key = part.substringBefore('=')
                val value = part.substringAfter('=', "")
                URLDecoder.decode(key, StandardCharsets.UTF_8.name()) to
                    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            }

        private fun localIpv4Address(): String? {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .sortedByDescending { it.name.startsWith("wlan") }

            return interfaces.asSequence()
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }

        private fun send(
            socket: Socket,
            status: Int,
            reason: String,
            body: String,
            contentType: String = "text/plain; charset=utf-8",
        ) {
            val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
            val header = buildString {
                append("HTTP/1.1 $status $reason\r\n")
                append("Content-Type: $contentType\r\n")
                append("Content-Length: ${bodyBytes.size}\r\n")
                append("X-Content-Type-Options: nosniff\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(StandardCharsets.US_ASCII)

            BufferedOutputStream(socket.getOutputStream()).use { output ->
                output.write(header)
                output.write(bodyBytes)
                output.flush()
            }
        }
    }
}

internal object UploadRules {
    fun sanitizeFileName(name: String): String {
        val cleaned = name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9 ._()'&-]"), "_")
            .trim(' ', '.')

        if (cleaned.length <= MAX_FILE_NAME_LENGTH) return cleaned

        val extension = cleaned.substringAfterLast('.', missingDelimiterValue = "")
        if (extension.isBlank() || extension.length >= MAX_FILE_NAME_LENGTH) {
            return cleaned.take(MAX_FILE_NAME_LENGTH)
        }

        val baseLength = MAX_FILE_NAME_LENGTH - extension.length - 1
        return "${cleaned.substringBeforeLast('.').take(baseLength)}.$extension"
    }

    private const val MAX_FILE_NAME_LENGTH = 180
}
