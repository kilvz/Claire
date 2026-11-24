package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.remote.RemoteAccess
import kotlinx.serialization.json.Json
import com.wangzan101.mika.core.remote.RemoteTaskApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Computer connection over USB: opened by the computer with adb, listening only on the local loopback, and the computer connects in via `adb forward`.
 * This is the contract with the computer: the contents of the connection info file and the script that runs on the computer. Both use only stock Android sh and commands available in toybox.
 */
object McpUsb {
    /** Name of the connection info file, placed in the app's external files directory: since Android 11 other apps cannot read it, while the computer reads it as the shell user over USB debugging. */
    const val INFO_FILE = "mcp-usb.json"

    /** Name of the script that runs on the computer, kept together with the "start from the computer" script. */
    const val SCRIPT = "connect-computer.sh"

    private val pretty = Json { prettyPrint = true }

    /**
     * Connection info: the address and request headers the MCP client on the computer has to fill in, plus the port forward to run first.
     * [boot] is this boot's id (the same value as `settings get global boot_count` reads in a shell), which the script uses to tell that it is not an old file left over from the previous boot.
     */
    fun info(port: Int, token: String, boot: String): String = pretty.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            put("api_version", 1)
            put("api_url", "http://127.0.0.1:$port${RemoteTaskApi.ROOT}")
            put("transport", "streamable-http")
            put("url", "http://127.0.0.1:$port${McpRpc.PATH}")
            putJsonObject("headers") { put("Authorization", "Bearer $token") }
            put("token", token)
            put("port", port)
            put("forward", forward(port))
            put("boot", boot)
        },
    ) + "\n"

    /** The command that forwards a local port on the computer to the phone's loopback port; it must be run again after unplugging and replugging the cable. */
    fun forward(port: Int) = "adb forward tcp:$port tcp:$port"

    /**
     * The script run by the computer over USB debugging (`adb shell sh <scriptPath>`; append `off` to turn it off). It carries no passphrase or token and can be left in place.
     * On: first run the "start from the computer" script [helperScript] (starting the phone helper if it is not running, or just saying a word if it is),
     * then start Claire's foreground service [component] with action [onAction] as the shell user, wait for this boot's connection info file [infoPath] to be written, and print it.
     * Off: start the same service with action [offAction] and wait for the connection info file to be deleted (the token is invalidated with it).
     * It does not run without shell identity: the service only accepts callers holding shell-level privileges, and an ordinary app cannot start it.
     */
    fun script(scriptPath: String, helperScript: String, infoPath: String, component: String, onAction: String, offAction: String, port: Int): String {
        val again = "adb shell sh $scriptPath"
        return """
            |#!/system/bin/sh
            |# Written by Claire. Lets AI tools on a computer use Claire over USB: $again   (turn off: $again off)
            |if [ "${'$'}(id -u)" != "2000" ]; then
            |  echo "Run this from a computer over USB: $again"
            |  exit 1
            |fi
            |info=${quote(infoPath)}
            |if [ "${'$'}1" = "off" ]; then
            |  out=${'$'}(am start-foreground-service -n ${quote(component)} -a ${quote(offAction)} 2>&1)
            |  case "${'$'}out" in *rror*|*xception*) echo "${'$'}out"; exit 1 ;; esac
            |  i=0
            |  while [ -f "${'$'}info" ]; do
            |    i=${'$'}((i + 1))
            |    if [ ${'$'}i -gt 10 ]; then echo "Claire did not confirm. Open Claire > Settings > Computer access (MCP) and turn off USB there."; exit 1; fi
            |    sleep 1
            |  done
            |  echo "Computer access over USB is off. The old token no longer works."
            |  exit 0
            |fi
            |if [ -f ${quote(helperScript)} ]; then
            |  sh ${quote(helperScript)} || echo "Phone control is not ready yet; tasks that operate apps wait until it is."
            |fi
            |boot=${'$'}(settings get global boot_count 2>/dev/null)
            |out=${'$'}(am start-foreground-service -n ${quote(component)} -a ${quote(onAction)} 2>&1)
            |case "${'$'}out" in *rror*|*xception*) echo "${'$'}out"; echo "Is Claire installed and up to date?"; exit 1 ;; esac
            |i=0
            |until grep -q "\"boot\": *\"${'$'}boot\"" "${'$'}info" 2>/dev/null; do
            |  i=${'$'}((i + 1))
            |  if [ ${'$'}i -gt 15 ]; then echo "Claire did not open the connection. Open Claire > Settings > Computer access (MCP) to see why, then run this again."; exit 1; fi
            |  sleep 1
            |done
            |echo "Computer access over USB is on. On the computer, run: ${forward(port)}"
            |echo "Then add this MCP server (Streamable HTTP) to your AI tool:"
            |cat "${'$'}info"
            |""".trimMargin()
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
