package com.lux032.musicbridge

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.lerp

/*
 * Design D key system (B × C).
 *
 *  Primary  – light "hi" key (near-white fill, dark field-tinted glyph). One per screen, or the
 *             split play | shuffle key.
 *  Tonal    – translucent white key, no outline: secondary actions.
 *  Bare     – glyph only, 48dp target: utilities (back, more, queue…).
 *  Toggle   – a key that inverts to the hi fill while on (favorite, repeat, shuffle mode).
 *
 * Shapes are squircles (16dp corner at 48dp) instead of the old outlined circles, and nothing
 * draws a border.
 */
internal object KeyColors {
    val Tonal = Color.White.copy(alpha = 0.11f)
    val TonalPressed = Color.White.copy(alpha = 0.16f)
    val Hi = Color(0xFFF2F6FB)
    val Line = Color.White.copy(alpha = 0.10f)
}

internal enum class KeyStyle { Tonal, Hi, Bare, Accent }

internal val KeyShape = RoundedCornerShape(16.dp)

/** Dark ink used on the light hi key: the current field's deep tone keeps it on-theme. */
@Composable
internal fun keyHiInk(): Color {
    val bottom = LocalArtworkPalette.current.backgroundBottom
    return lerp(bottom, Color.Black, 0.15f)
}

/** Docked chrome (navigation bar, room bar): a slightly deeper version of the page base. */
@Composable
internal fun dockColor(): Color = lerp(LocalArtworkPalette.current.backgroundBottom, Color.Black, 0.28f)

@Composable
internal fun keyContainer(style: KeyStyle): Color = when (style) {
    KeyStyle.Tonal -> KeyColors.Tonal
    KeyStyle.Hi -> KeyColors.Hi
    KeyStyle.Bare -> Color.Transparent
    KeyStyle.Accent -> LocalArtworkPalette.current.accent
}

@Composable
internal fun keyContent(style: KeyStyle): Color = when (style) {
    KeyStyle.Tonal -> AppColors.TextPrimary
    KeyStyle.Hi -> keyHiInk()
    KeyStyle.Bare -> AppColors.TextSecondary
    KeyStyle.Accent -> LocalArtworkPalette.current.onAccent
}

/**
 * Base key: squircle container, press-scale feedback, content colour provided through
 * [LocalContentColor] so plain `Icon`s and `Text`s pick it up.
 */
@Composable
internal fun Key(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: KeyStyle = KeyStyle.Tonal,
    enabled: Boolean = true,
    shape: Shape = KeyShape,
    containerColor: Color? = null,
    contentColor: Color? = null,
    contentDescription: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.92f else 1f, label = "key_scale")
    val targetContainer = containerColor ?: keyContainer(style)
    val container by animateColorAsState(targetContainer, label = "key_container")
    val targetContent = contentColor ?: keyContent(style)
    val content by animateColorAsState(targetContent, label = "key_content")
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(container)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                role = Role.Button,
                onClick = onClick,
            )
            .then(
                if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription }
                else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (enabled) content else content.copy(alpha = 0.38f),
        ) {
            content()
        }
    }
}

/** Square icon key (48dp default). */
@Composable
internal fun IconKey(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: KeyStyle = KeyStyle.Tonal,
    enabled: Boolean = true,
    size: Dp = 48.dp,
    iconSize: Dp = 22.dp,
    tint: Color? = null,
) {
    Key(
        onClick = onClick,
        modifier = modifier.size(size),
        style = style,
        enabled = enabled,
        shape = RoundedCornerShape(size / 3),
        contentDescription = contentDescription,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint ?: LocalContentColor.current,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Bare glyph button: no container, 48dp touch target. */
@Composable
internal fun BareIcon(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = AppColors.TextSecondary,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
) {
    Key(
        onClick = onClick,
        modifier = modifier.size(size),
        style = KeyStyle.Bare,
        enabled = enabled,
        shape = RoundedCornerShape(size / 3),
        contentColor = tint,
        contentDescription = contentDescription,
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(iconSize))
    }
}

/** Toggle key: tonal (or bare) while off, inverted to the hi fill while on. */
@Composable
internal fun ToggleKey(
    checked: Boolean,
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    checkedIcon: ImageVector = icon,
    enabled: Boolean = true,
    bareWhenOff: Boolean = false,
    size: Dp = 48.dp,
    iconSize: Dp = 22.dp,
) {
    IconKey(
        icon = if (checked) checkedIcon else icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        style = when {
            checked -> KeyStyle.Hi
            bareWhenOff -> KeyStyle.Bare
            else -> KeyStyle.Tonal
        },
        enabled = enabled,
        size = size,
        iconSize = iconSize,
    )
}

/** Labelled key: icon + text, 48dp tall. */
@Composable
internal fun LabelKey(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: KeyStyle = KeyStyle.Tonal,
    enabled: Boolean = true,
    height: Dp = 48.dp,
    isLoading: Boolean = false,
    shape: Shape = KeyShape,
) {
    Key(
        onClick = onClick,
        modifier = modifier.heightIn(min = height),
        style = style,
        enabled = enabled && !isLoading,
        shape = shape,
    ) {
        Row(
            modifier = Modifier.padding(start = if (icon != null) 14.dp else 18.dp, end = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = LocalContentColor.current,
                    strokeWidth = 2.dp,
                )
            } else if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Text(
                text = text,
                fontWeight = FontWeight.Bold,
                fontSize = 14.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Split primary key: [▶ label | ⤮]. Playing and shuffling are two ways of doing one thing, so
 * they share one light key, separated by a 3dp seam.
 */
@Composable
internal fun SplitPlayKey(
    label: String,
    onPlay: () -> Unit,
    onShuffle: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shuffleEnabled: Boolean = enabled,
    isLoading: Boolean = false,
    shuffleContentDescription: String = Strings.shuffleShort,
) {
    Row(
        modifier = modifier.height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        LabelKey(
            text = label,
            icon = Icons.Filled.PlayArrow,
            onClick = onPlay,
            style = KeyStyle.Hi,
            enabled = enabled,
            isLoading = isLoading,
            shape = if (onShuffle != null) {
                RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp, topEnd = 6.dp, bottomEnd = 6.dp)
            } else {
                KeyShape
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        if (onShuffle != null) {
            Key(
                onClick = onShuffle,
                style = KeyStyle.Hi,
                enabled = shuffleEnabled,
                shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 16.dp, bottomEnd = 16.dp),
                contentDescription = shuffleContentDescription,
                modifier = Modifier
                    .width(52.dp)
                    .fillMaxHeight(),
            ) {
                Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** Small "status" dot: the room is online / output is live. */
@Composable
internal fun LiveDot(modifier: Modifier = Modifier, color: Color = LocalArtworkPalette.current.accent) {
    Box(
        modifier = modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/** Output-room key: "● 卧室 ⌄". Opens the output picker. */
@Composable
internal fun RoomKey(
    targetName: String,
    isSonos: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    height: Dp = 40.dp,
) {
    Key(
        onClick = onClick,
        modifier = modifier.height(height),
        style = KeyStyle.Tonal,
        shape = RoundedCornerShape(14.dp),
        contentDescription = Strings.selectOutputDevice,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSonos) {
                LiveDot()
            } else {
                Icon(
                    Icons.Filled.PhoneAndroid,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalArtworkPalette.current.accent,
                )
            }
            Text(
                text = if (trailing != null) "$targetName · $trailing" else targetName,
                fontWeight = FontWeight.Bold,
                fontSize = 13.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = AppColors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Section header: serif title with an optional quiet "全部 ›" action. */
@Composable
internal fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = AppColors.TextPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (actionLabel != null && onAction != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onAction)
                    .heightIn(min = 40.dp)
                    .padding(start = 10.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = actionLabel,
                    color = AppColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = AppColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Hairline used between list rows (B's editorial lists instead of per-row cards). */
@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(1.dp)
            .background(KeyColors.Line),
    )
}

/**
 * Borderless choice / list row. The selected row gets the tonal fill and a trailing accent
 * check; unselected rows are bare. Replaces the old outlined per-row cards.
 */
@Composable
internal fun ChoiceRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showCheck: Boolean = true,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val container by animateColorAsState(
        if (selected) KeyColors.Tonal else Color.Transparent,
        label = "choice_row",
    )
    Row(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        if (selected && showCheck) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = LocalArtworkPalette.current.accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Search field on a tonal squircle fill: no outline, leading search glyph, clear key. */
@Composable
internal fun TonalSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = 52.dp),
        placeholder = { Text(placeholder, color = AppColors.TextTertiary) },
        singleLine = true,
        shape = KeyShape,
        leadingIcon = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = AppColors.TextSecondary,
            )
        },
        trailingIcon = if (value.isNotEmpty()) {
            {
                BareIcon(
                    icon = Icons.Filled.Close,
                    contentDescription = Strings.clear,
                    onClick = { onValueChange("") },
                    iconSize = 20.dp,
                )
            }
        } else {
            null
        },
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = KeyColors.TonalPressed,
            unfocusedContainerColor = KeyColors.Tonal,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            cursorColor = LocalArtworkPalette.current.accent,
            focusedTextColor = AppColors.TextPrimary,
            unfocusedTextColor = AppColors.TextPrimary,
        ),
    )
}

/** Drop-in for M3 `Button` / `OutlinedButton` with the key look (48dp squircle, no outline). */
@Composable
internal fun KeyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: KeyStyle = KeyStyle.Tonal,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Key(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        style = style,
        enabled = enabled,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.5.sp,
                ),
            ) {
                content()
            }
        }
    }
}
