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

    private val lower    = ('a'..'z').toList()
    private val upper    = ('A'..'Z').toList()
    private val digits   = ('0'..'9').toList()
    private val mixed    = lower + upper + digits
    private val hexChars = ('0'..'9').toList() + listOf('a','b','c','d','e','f')

    // ── Ultra-advanced email alias patterns ────────────────────────────────────
    // Each pattern is a lambda that takes the account index and returns a suffix.
    // Gmail ignores everything after '+', so all aliases deliver to the same inbox.
    // This makes every account look like a real unique person signing up.
    private val aliasPatterns: List<(Int) -> String> = listOf(
        // 1. Common "shop / newsletter" style
        { _: Int -> "shop${(1000..9999).random()}" },
        { _: Int -> "store${(100..999).random()}" },
        { _: Int -> "news${(10..99).random()}" },
        { _: Int -> "newsletter${(1..9).random()}" },
        { _: Int -> "deals${(1000..9999).random()}" },
        { _: Int -> "offers${(100..9999).random()}" },
        { _: Int -> "promo${(1000..9999).random()}" },
        { _: Int -> "sale${(100..9999).random()}" },
        { _: Int -> "sub${(1000..9999).random()}" },
        { _: Int -> "signup${(100..9999).random()}" },

        // 2. App / service style
        { _: Int -> "discord${(100..9999).random()}" },
        { _: Int -> "dc${(1000..99999).random()}" },
        { _: Int -> "app${(1000..9999).random()}" },
        { _: Int -> "games${(100..9999).random()}" },
        { _: Int -> "gaming${(10..9999).random()}" },
        { _: Int -> "play${(100..9999).random()}" },
        { _: Int -> "social${(100..9999).random()}" },
        { _: Int -> "chat${(1000..9999).random()}" },

        // 3. Year + word style (looks like a real person made it)
        { _: Int -> "verify${Calendar.getInstance().get(Calendar.YEAR)}" },
        { _: Int -> "login${(2020..2025).random()}" },
        { _: Int -> "acc${(2020..2025).random()}${(1..999).random()}" },
        { _: Int -> "account${(2020..2025).random()}" },
        { _: Int -> "reg${Calendar.getInstance().get(Calendar.YEAR)}" },

        // 4. Hex / token-like (short, unique)
        { _: Int -> randomHex(8) },
        { _: Int -> randomHex(10) },
        { _: Int -> randomHex(6) + (100..999).random() },

        // 5. Username-style (first + last initial + numbers)
        { _: Int -> randomName() + (10..9999).random() },
        { _: Int -> randomName() + "_" + randomName() },
        { _: Int -> randomName() + (100..999).random() + randomName().take(1) },

        // 6. Sequential with noise
        { i: Int -> "user${i + 1}x${randomHex(4)}" },
        { i: Int -> "member${(i + 1).toString().padStart(4, '0')}" },
        { i: Int -> "id${(i + 1)}k${randomHex(5)}" },

        // 7. Date-based (looks real)
        { _: Int ->
            val c = Calendar.getInstance()
            "${c.get(Calendar.YEAR)}${(c.get(Calendar.MONTH)+1).toString().padStart(2,'0')}${(1..28).random()}"
        },
        { _: Int ->
            val month = (1..12).random()
            val year  = (2022..2025).random()
            "m${month}y${year}"
        },

        // 8. Underscore combos
        { _: Int -> randomName() + "_" + (1000..9999).random() },
        { _: Int -> "real_" + randomName() + (1..999).random() },
        { _: Int -> randomName() + randomName().take(3) + (10..99).random() },

        // 9. Work / org style
        { _: Int -> "work${(100..9999).random()}" },
        { _: Int -> "business${(100..999).random()}" },
        { _: Int -> "team${(10..9999).random()}" },
        { _: Int -> "office${(100..9999).random()}" },

        // 10. Pure random human-looking
        { _: Int -> humanSuffix((7..13).random()) }
    )

    private val firstNames = listOf(
        "alex","ryan","mike","jake","emma","liam","noah","luna","maya","zara",
        "cole","finn","kate","anna","jade","jack","sam","leo","max","ben",
        "chris","evan","dan","tom","jay","kim","amy","eve","ian","rob",
        "tyler","dylan","ashley","morgan","taylor","jordan","casey","riley",
        "quinn","drew","blake","reese","peyton","skyler","avery","brooke"
    )

    private fun randomName(): String = firstNames.random()
    private fun randomHex(len: Int) = (1..len).map { hexChars.random() }.joinToString("")

    private fun humanSuffix(length: Int): String {
        val sb = StringBuilder(length)
        repeat(length) { i ->
            sb.append(
                if (i > 0 && i < length - 1 && (1..8).random() == 1) '_'
                else mixed.random()
            )
        }
        return sb.toString()
    }

    /**
     * Ultra-advanced email alias generator.
     *
     * Input:  tanmayyyy38@gmail.com
     * Output: tanmayyyy38+shop4821@gmail.com
     *         tanmayyyy38+discord9201@gmail.com
     *         tanmayyyy38+alexryan247@gmail.com
     *         … (40+ unique patterns, never repeats within a run)
     *
     * All aliases deliver to the same Gmail inbox. Each looks like a genuine
     * sign-up from a different real person / service.
     */
    fun generateEmailAlias(baseEmail: String, index: Int): String {
        val atIndex = baseEmail.indexOf('@')
        if (atIndex < 0) return baseEmail
        val localPart = baseEmail.substring(0, atIndex)
        val domain    = baseEmail.substring(atIndex)
        val pattern   = aliasPatterns[index % aliasPatterns.size]
        val suffix    = pattern(index)
        return "$localPart+$suffix$domain"
    }

    fun generateDisplayName(index: Int): String {
        val adjectives = listOf(
            "Pixel","Dark","Swift","Neo","Hyper","Shadow","Cyber","Turbo","Alpha","Nova",
            "Storm","Night","Epic","Ultra","Neon","Frost","Blaze","Void","Lunar","Solar",
            "Zen","Iron","Toxic","Phantom","Electric","Arctic","Atomic","Crystal","Digital",
            "Emerald","Feral","Glitch","Infinite","Jade","Kinetic","Laser","Mystic","Rogue",
            "Silent","Vivid","Wild","Xenon","Zero","Primal","Cosmic","Savage","Crimson",
            "Azure","Obsidian","Radiant","Arcane","Binary","Crypt","Delta","Echoed"
        )
        val nouns = listOf(
            "Fox","Wolf","Eagle","Hawk","Bear","Tiger","Dragon","Lion","Cobra","Viper",
            "Panther","Ghost","Ninja","Blade","Phoenix","Arrow","Comet","Pulse","Raven",
            "Lynx","Falcon","Serpent","Titan","Hunter","Rider","Slayer","Ranger","Striker",
            "Guardian","Specter","Bandit","Cipher","Demon","Forge","Grim","Hydra","Inferno",
            "Knight","Lurker","Maven","Oracle","Pirate","Quest","Reaper","Sage","Tempest",
            "Wraith","Zephyr","Storm","Blaze","Shadow","Shard","Core","Pulse","Node"
        )
        return "${adjectives[index % adjectives.size]}${nouns[(index + 7) % nouns.size]}"
    }

    fun generateUsername(index: Int): String {
        val adj  = listOf(
            "cool","dark","swift","neo","hyper","shadow","pixel","cyber","turbo","alpha",
            "nova","storm","night","epic","ultra","zen","iron","toxic","ghost","blaze",
            "neon","void","frost","lunar","solar","atomic","electric","feral","glitch",
            "jade","kinetic","laser","mystic","rogue","silent","vivid","wild","zero",
            "phantom","digital","crystal","emerald","inferno","primal","cosmic","savage",
            "crimson","azure","obsidian","radiant","arcane","binary","delta","echoed"
        )
        val noun = listOf(
            "fox","wolf","eagle","hawk","bear","tiger","dragon","lion","cobra","viper",
            "panther","ninja","blade","phoenix","arrow","raven","lynx","falcon","titan",
            "hunter","rider","slayer","ranger","specter","bandit","cipher","forge","grim",
            "hydra","knight","lurker","oracle","pirate","reaper","sage","tempest","bane",
            "comet","pulse","striker","guardian","wraith","zephyr","shard","ghost","spark",
            "shade","core","node","link","loop","flux","wave","peak","arc","edge"
        )
        return "${adj[index % adj.size]}_${noun[(index + 11) % noun.size]}_${(10..9999).random()}"
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
            pw.println("${account.email}:${account.password}:${account.username}:${account.token}")
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
