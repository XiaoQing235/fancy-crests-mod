package dev.fancycrests.adapter.luckperms

import dev.fancycrests.core.config.LegacyTextParser
import java.util.UUID
import net.luckperms.api.LuckPermsProvider
import net.minecraft.network.chat.Component

/** Common LuckPerms values for versions without a compatible placeholder expansion. */
internal object LuckPermsPlaceholders {
    fun resolve(uuid: UUID, token: String): Component? {
        val key = when {
            token.startsWith("luckperms:") -> token.removePrefix("luckperms:")
            token.startsWith("luckperms_") -> token.removePrefix("luckperms_")
            else -> return null
        }
        val user = LuckPermsProvider.get().userManager.getUser(uuid) ?: return null
        val meta = user.cachedData.metaData
        val value = when {
            key == "prefix" -> meta.prefix ?: ""
            key == "suffix" -> meta.suffix ?: ""
            key == "primary_group_name" -> user.primaryGroup
            key.startsWith("meta ") -> meta.getMetaValue(key.removePrefix("meta ")) ?: ""
            key.startsWith("meta_") -> meta.getMetaValue(key.removePrefix("meta_")) ?: ""
            else -> return null
        }
        return LegacyTextParser.parse(value)
    }
}
