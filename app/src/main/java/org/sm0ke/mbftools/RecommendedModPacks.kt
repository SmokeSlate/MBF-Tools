package org.sm0ke.mbftools

data class RecommendedMod(val id: String, val displayName: String)

data class RecommendedModPack(
        val title: String,
        val versionTag: String,
        val mods: List<RecommendedMod>,
        val bundledQmodFileName: String? = null,
        val bundledQmodBase64: String? = null
) {
    val fingerprint: String
        get() = buildString {
            append(versionTag)
            append('|')
            append(title)
            append('|')
            append(mods.joinToString(separator = ",") { it.id })
        }
}

object RecommendedModPacks {
    private val modernPack =
            listOf(
                    RecommendedMod(id = "BetterSongSearch", displayName = "BetterSongSearch"),
                    RecommendedMod(id = "bsl", displayName = "Better Song List"),
                    RecommendedMod(id = "SongDownloader", displayName = "SongDownloader"),
                    RecommendedMod(id = "BeatLeader", displayName = "BeatLeader"),
                    RecommendedMod(id = "transitions", displayName = "Transitions")
            )

    private val pack1408_7379 =
            listOf(
                    RecommendedMod(id = "FakeId", displayName = "Fake ID"),
                    RecommendedMod(id = "datakeeper", displayName = "PlayerDataKeeper"),
                    RecommendedMod(id = "playlistcore", displayName = "PlaylistCore"),
                    RecommendedMod(id = "songcore", displayName = "SongCore"),
                    RecommendedMod(id = "songdownloader", displayName = "SongDownloader"),
                    RecommendedMod(id = "unicode", displayName = "Unicode"),
                    RecommendedMod(id = "BeatLeader", displayName = "BeatLeader"),
                    RecommendedMod(id = "BetterSongSearch", displayName = "BetterSongSearch"),
                    RecommendedMod(id = "chroma", displayName = "Chroma"),
                    RecommendedMod(id = "custommodels", displayName = "Custom Models"),
                    RecommendedMod(id = "graphicstweaks", displayName = "GraphicsTweaks"),
                    RecommendedMod(id = "HitScoreVisualizer", displayName = "HitScoreVisualizer"),
                    RecommendedMod(id = "IntroSkip", displayName = "Intro Skip"),
                    RecommendedMod(id = "MappingExtensions", displayName = "Mapping Extensions"),
                    RecommendedMod(id = "NoodleExtensions", displayName = "NoodleExtensions"),
                    RecommendedMod(id = "NoPromo", displayName = "NoPromo"),
                    RecommendedMod(id = "playlistdownloader", displayName = "PlaylistDownloader"),
                    RecommendedMod(id = "playlistmanager", displayName = "PlaylistManager"),
                    RecommendedMod(
                            id = "qbeatsaberplus-multiplayer",
                            displayName = "QBeatSaberPlus-Multiplayer"
                    ),
                    RecommendedMod(id = "RecentlyPlayed", displayName = "RecentlyPlayed"),
                    RecommendedMod(id = "replay", displayName = "Replay")
            )

    private const val pack1408_7379QmodFileName = "mods.qmod"

    private const val pack1408_7379QmodBase64 =
            "UEsDBBQAAAAIAI6jxFy5XpCHsgQAAJwhAAAIAAAAbW9kLmpzb269mV1v4jgUhu8r9T+gSnu32ITCFOZmNS3b3WrLiALbm9UsMolLLJzYtU07dDT/fWwn4TOhEya4Fy3Fr3PePDnHX/l2flbTPxeTh8EjFpKw+OKj/rcBPOBd/J42xijC9ush9lkU4TjAQa3PAll7YqLmgVYDdCZXl1fdVQ8SWH3/+nYyZozKie6pO0zytGihQiasfhSxOR5RpPCqNcDSF4SrzFmPvcaUIR1bhbgmNgxFhwy9bNycBxqgsWrhyJ+jGb5LHOvLgSlGaqZvWdpPEk2x2FU/bl1uP5z2Ykzi9LZ8piiKg+Zm+y2hWJrm/87Pkm+3fr5kUkqmAonlO/KtLk9ae8M4OdxhJfcZX/75VeHY3NI7MTa6BZgb8LG/jlPQpVb7dqDNXixNmKmMaAapWLz5MP9vgBZot9/vFKSJc/fUJ1KSeGa7h0px+RHCGVHhYgr044dT+VznTCgJHxZYqvr1qH8PBaYYSSxhdhn4kgSGphk86wd6ccDB90P2fhaOv5CKRXW15Bp4WUheB1xWCMmiGSTF0Eex/i3gHW3ecG5bxtrjACk/1JfIZ2f8wBt7R0Ys3SC8RXNT6aXhgW6F7HooHoeGWqd5Vb/WQ8zIDDEJUmAd9gqYge5vzeuN8QYaNbjruYFHESdvpdk1QbPKxBOIxBhhhUWMKLw3lvJhmbhJuxs6EVbIZwKXBOSBD6BZIZ++tkGJsn9vtJ08ODbmSuEGD0cci+ZE7k6FP4mppS23KsR0q/NHLyAC/AKtM8pyxykbFu56d0MsQEpXt55kxRE1V+WEmKXUgKIlFj1t6x9rq6ju2ntKRymmo1Ii1VFV2D4ZMmOpuBLbKa5M5QaVZPHsKEweaFZZhtujebLeGmlvxbyMAZhJ3MHKLBxRjB3QqZBYgMQ8JrNQeY12w4LorazlF6SOv6Nzg20RE58FZVPMrHKuTlCJ/yZubIoVra6uMpUbQGbpd4+PyqnuegNbAaO1EWh2vNR+rBsG+aR09I0+rmApPUqYPB5hJPywJDK9GgSed5Jd4q6zwhRLPNSnsp4s4fd6OtpGhoJFqDQ/r1HpOnXN78b6yQdmgqYCl3tsHQnTsntszzzeE4xcySa5by3lz4k67pbKDaqZQDwkvlSvGM2POJCodFq8FewNx58o/gr/Sn2Nra+isb6zo3PD7G+iRmbd9UjkQj/ct9JD/yVoVJpkejDQC0/GQyxeMYlDJAIF913mUbRWcrRuSN7FSrDRnPAjqrTKcWzIlppf7HW7XbjyVC+cA2z4tXCSfOEGWR9xru9k46i39L6yXekhovAxXSoM94zlkEuC70stuj+GzZPT+8xYQPHR8Ko+51nPn7vGik98dpVu0u4zG+j5mx0xQXhVJtvBU9fUY9Fk4V3unLumerfnGr+wB708ycokO7d4bweqo+do3cKLkpcUpau2dVJy2auT3JJtbWBLhW6YPa/ee3K6kPVoQRXh9kivJL4PFeMzyxOfc/iwqt+BMdhfG6xn+ZWH1No50NkN3SH2cazo0h6Sln0XtfXuugKiY2Qmg5DAbVf5CalD7+gm3qTVmHTcgBPYPKbSY1/7JO9XhtZM/ninI6bt74MpaPtyfvb9B1BLAQIUABQAAAAIAI6jxFy5XpCHsgQAAJwhAAAIAAAAAAAAAAAAAAAAAAAAAABtb2QuanNvblBLBQYAAAAAAQABADYAAADYBAAAAAA="

    private val packsByVersion =
            mapOf(
                    "1.40.8_7379" to
                            RecommendedModPack(
                                    title = "Recommended Mods for 1.40.8_7379",
                                    versionTag = "1.40.8_7379",
                                    mods = pack1408_7379,
                                    bundledQmodFileName = pack1408_7379QmodFileName,
                                    bundledQmodBase64 = pack1408_7379QmodBase64
                            ),
                    "1.40.7_7060" to
                            RecommendedModPack(
                                    title = "Recommended Essentials",
                                    versionTag = "1.40.7_7060",
                                    mods = modernPack
                            ),
                    "1.40.6_6407" to
                            RecommendedModPack(
                                    title = "Recommended Essentials",
                                    versionTag = "1.40.6_6407",
                                    mods = modernPack
                            ),
                    "1.40.4_5283" to
                            RecommendedModPack(
                                    title = "Recommended Essentials",
                                    versionTag = "1.40.4_5283",
                                    mods = modernPack
                            )
            )

    fun forVersion(versionTag: String?): RecommendedModPack? {
        if (versionTag.isNullOrBlank()) {
            return null
        }
        val trimmed = versionTag.trim()
        packsByVersion[trimmed]?.let { return it }

        val normalized = normalizeVersionTag(trimmed)
        packsByVersion[normalized]?.let { return it }

        val versionName = versionNameOf(normalized)
        val byVersionName =
                packsByVersion.entries.filter { versionNameOf(it.key) == versionName }.map { it.value }
        if (byVersionName.size == 1) {
            return byVersionName.first()
        }

        return null
    }

    fun supportedVersionTags(): Set<String> = packsByVersion.keys

    private fun normalizeVersionTag(versionTag: String): String {
        return versionTag.trim().replace('+', '_').replace(' ', '_')
    }

    private fun versionNameOf(versionTag: String): String {
        return normalizeVersionTag(versionTag).substringBefore('_')
    }
}
