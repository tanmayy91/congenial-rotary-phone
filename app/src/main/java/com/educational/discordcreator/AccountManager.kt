package com.educational.discordcreator

import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.util.Calendar

data class AccountInfo(
    val email: String,
    val displayName: String,
    val username: String,
    val password: String,
    val birthMonth: Int,
    val birthDay: Int,
    val birthYear: Int,
    var token: String = "",
    var status: String = "pending"
)

object AccountManager {

    private val charPool     = ('a'..'z') + ('0'..'9')
    private val mixedPool    = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    private val specialChars = listOf('_', '-', '.')

    fun randomString(length: Int = 8): String =
        (1..length).map { charPool.random() }.joinToString("")

    private fun humanSuffix(length: Int): String {
        val sb = StringBuilder(length)
        repeat(length) { i ->
            sb.append(
                if (i > 0 && i < length - 1 && (1..8).random() == 1) specialChars.random()
                else mixedPool.random()
            )
        }
        return sb.toString()
    }

    fun generateEmailAlias(baseEmail: String, index: Int): String {
        val atIndex = baseEmail.indexOf('@')
        if (atIndex < 0) return baseEmail
        val localPart = baseEmail.substring(0, atIndex)
        val domain = baseEmail.substring(atIndex)
        val length = 7 + (index % 6)
        val suffix = humanSuffix(length)
        return "$localPart+$suffix$domain"
    }

    fun generateDisplayName(index: Int): String {
        val adjectives = listOf(
            "Pixel", "Dark", "Swift", "Neo", "Hyper", "Shadow", "Cyber",
            "Turbo", "Alpha", "Nova", "Storm", "Night", "Epic", "Ultra",
            "Neon", "Frost", "Blaze", "Void", "Lunar", "Solar", "Zen",
            "Iron", "Toxic", "Phantom", "Electric", "Arctic", "Atomic",
            "Blazing", "Crystal", "Digital", "Emerald", "Feral", "Glitch",
            "Hidden", "Infinite", "Jade", "Kinetic", "Laser", "Mystic",
            "Rogue", "Silent", "Vivid", "Wild", "Xenon", "Zero", "Primal",
            "Cosmic", "Savage", "Crimson", "Azure", "Obsidian", "Radiant"
        )
        val nouns = listOf(
            "Fox", "Wolf", "Eagle", "Hawk", "Bear", "Tiger", "Dragon", "Lion",
            "Cobra", "Viper", "Panther", "Ghost", "Ninja", "Blade", "Phoenix",
            "Arrow", "Comet", "Pulse", "Raven", "Lynx", "Falcon", "Serpent",
            "Titan", "Hunter", "Rider", "Slayer", "Ranger", "Striker", "Guardian",
            "Specter", "Bandit", "Cipher", "Demon", "Element", "Forge", "Grim",
            "Hydra", "Inferno", "Jester", "Knight", "Lurker", "Maven", "Oracle",
            "Pirate", "Quest", "Reaper", "Sage", "Tempest", "Unity", "Vanquish",
            "Wraith", "Zephyr", "Storm", "Blaze", "Shadow", "Shard", "Void"
        )
        val adj  = adjectives[index % adjectives.size]
        val noun = nouns[(index + 7) % nouns.size]
        return "$adj$noun"
    }

    fun generateUsername(index: Int): String {
        val adjectives = listOf(
            "cool", "dark", "swift", "neo", "hyper", "shadow", "pixel", "cyber",
            "turbo", "alpha", "nova", "storm", "night", "epic", "ultra", "zen",
            "iron", "toxic", "ghost", "blaze", "neon", "void", "frost", "lunar",
            "solar", "atomic", "blazing", "electric", "feral", "glitch", "hidden",
            "jade", "kinetic", "laser", "mystic", "rogue", "silent", "vivid",
            "wild", "zero", "phantom", "digital", "crystal", "emerald", "inferno",
            "primal", "cosmic", "savage", "crimson", "azure", "obsidian", "radiant"
        )
        val nouns = listOf(
            "fox", "wolf", "eagle", "hawk", "bear", "tiger", "dragon", "lion",
            "cobra", "viper", "panther", "ninja", "blade", "phoenix", "arrow",
            "raven", "lynx", "falcon", "titan", "hunter", "rider", "slayer",
            "ranger", "specter", "bandit", "cipher", "demon", "forge", "grim",
            "hydra", "jester", "knight", "lurker", "oracle", "pirate", "reaper",
            "sage", "tempest", "bane", "comet", "pulse", "striker", "guardian",
            "wraith", "zephyr", "shard", "ghost", "void", "shade", "spark"
        )
        val adj  = adjectives[index % adjectives.size]
        val noun = nouns[(index + 11) % nouns.size]
        val num  = (10..9999).random()
        return "${adj}_${noun}_$num"
    }

    fun buildAccountList(
        baseEmail: String,
        password: String,
        count: Int,
        groqUsernames: List<String> = emptyList(),
        groqDisplayNames: List<String> = emptyList()
    ): List<AccountInfo> {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        return (0 until count).map { i ->
            AccountInfo(
                email       = generateEmailAlias(baseEmail, i),
                displayName = groqDisplayNames.getOrNull(i) ?: generateDisplayName(i),
                username    = groqUsernames.getOrNull(i)    ?: generateUsername(i),
                password    = password,
                birthMonth  = (1..12).random(),
                birthDay    = (1..28).random(),
                birthYear   = ((currentYear - 28)..(currentYear - 18)).random()
            )
        }
    }

    fun saveAccount(filesDir: File, account: AccountInfo) {
        val accFile    = File(filesDir, "acc.txt")
        val tokensFile = File(filesDir, "tokens.txt")
        PrintWriter(FileWriter(accFile, true)).use { pw ->
            pw.println("${account.email}:${account.password}:${account.token}")
        }
        if (account.token.isNotBlank()) {
            PrintWriter(FileWriter(tokensFile, true)).use { pw ->
                pw.println(account.token)
            }
        }
    }

    fun readAccFile(filesDir: File): String {
        val file = File(filesDir, "acc.txt")
        return if (file.exists()) file.readText() else "(empty)"
    }

    fun readTokensFile(filesDir: File): String {
        val file = File(filesDir, "tokens.txt")
        return if (file.exists()) file.readText() else "(empty)"
    }

    fun clearFiles(filesDir: File) {
        File(filesDir, "acc.txt").delete()
        File(filesDir, "tokens.txt").delete()
    }

    fun getAccFile(filesDir: File): File    = File(filesDir, "acc.txt")
    fun getTokensFile(filesDir: File): File = File(filesDir, "tokens.txt")
}
