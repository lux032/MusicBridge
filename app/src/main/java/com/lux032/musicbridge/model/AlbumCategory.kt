package com.lux032.musicbridge.model

import com.lux032.musicbridge.plex.PlexAlbum
import java.text.Normalizer
import java.util.Locale

enum class ArtistAlbumCategory { Album, SingleEp, CompilationLive }

enum class ReleaseKind { Album, Ep, Single, Compilation, Live }

data class ArtistAlbumClassification(
    val category: ArtistAlbumCategory,
    val kind: ReleaseKind,
)

private val explicitSingle = Regex("(?:^|[\\s(\\[{-])SINGLE(?:$|[\\s)\\]}-])")
private val explicitEp = Regex("(?:^|[\\s(\\[{-])EP(?:$|[\\s)\\]}-])")
private val explicitLive = Regex("(?:^|[\\s(\\[{-])LIVE(?:$|[\\s)\\]}-])|\\bLIVE AT\\b|\\bLIVE IN\\b|\\bCONCERT\\b|\\bTOUR\\b|现场|ライヴ|ライブ")
private val explicitCompilation = Regex("^(?:THE )?BEST$|^ベスト$|\\bBEST OF\\b|\\bGREATEST HITS\\b|\\bANTHOLOGY\\b|精选|精選|合集|合輯")
private val explicitMiniAlbum = Regex("\\bMINI[ -]?ALBUM\\b|ミニアルバム")

fun classifyArtistAlbum(album: PlexAlbum): ArtistAlbumClassification {
    val subformats = album.subformat
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        ?: emptyList()
    if (subformats.any { it.equals("Live", ignoreCase = true) }) {
        return ArtistAlbumClassification(ArtistAlbumCategory.CompilationLive, ReleaseKind.Live)
    }
    if (subformats.any { it.equals("Compilation", ignoreCase = true) }) {
        return ArtistAlbumClassification(ArtistAlbumCategory.CompilationLive, ReleaseKind.Compilation)
    }

    val format = album.format?.trim()
    if (format.equals("Single", ignoreCase = true)) {
        return ArtistAlbumClassification(ArtistAlbumCategory.SingleEp, ReleaseKind.Single)
    }
    if (format.equals("EP", ignoreCase = true)) {
        return ArtistAlbumClassification(ArtistAlbumCategory.SingleEp, ReleaseKind.Ep)
    }
    if (format.equals("Album", ignoreCase = true)) {
        return ArtistAlbumClassification(ArtistAlbumCategory.Album, ReleaseKind.Album)
    }

    val normalizedTitle = Normalizer.normalize(album.title, Normalizer.Form.NFKC)
        .trim()
        .uppercase(Locale.ROOT)
    if (explicitLive.containsMatchIn(normalizedTitle)) {
        return ArtistAlbumClassification(ArtistAlbumCategory.CompilationLive, ReleaseKind.Live)
    }
    if (explicitCompilation.containsMatchIn(normalizedTitle)) {
        return ArtistAlbumClassification(ArtistAlbumCategory.CompilationLive, ReleaseKind.Compilation)
    }
    if (explicitSingle.containsMatchIn(normalizedTitle)) {
        return ArtistAlbumClassification(ArtistAlbumCategory.SingleEp, ReleaseKind.Single)
    }
    if (explicitEp.containsMatchIn(normalizedTitle) || explicitMiniAlbum.containsMatchIn(normalizedTitle)) {
        return ArtistAlbumClassification(ArtistAlbumCategory.SingleEp, ReleaseKind.Ep)
    }

    return when (album.leafCount?.takeIf { it > 0 }) {
        in 1..3 -> ArtistAlbumClassification(ArtistAlbumCategory.SingleEp, ReleaseKind.Single)
        in 4..6 -> ArtistAlbumClassification(ArtistAlbumCategory.SingleEp, ReleaseKind.Ep)
        else -> ArtistAlbumClassification(ArtistAlbumCategory.Album, ReleaseKind.Album)
    }
}
