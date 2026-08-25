package com.symmetricalpalmtree.paintsproutonyx.data.soil

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A row of the `sketchbook` table — the universal object row of a `.soil` file.
 * The DDL these annotations must generate is pinned in [SoilSchema]; if the two
 * ever disagree, the schema contract wins and this entity is what moved.
 *
 * Wide and sparse: every payload column is nullable and each row type uses the
 * few it needs. Columns are shared by role across types — read [type] first,
 * then interpret:
 *  - sketchbook: [text] = title, [refId] = last-open page id
 *  - page: [refId] = the paper row's id, [width]/[height] px
 *  - mark: [color] `#RRGGBB`/`#AARRGGBB`, [strokeWidth] px, [style] = the
 *    g-paper StrokeStyle name, [blob] = format-B geometry (MarkCodec)
 *  - paper: [text] = the paper identity, [blob] = a WEBP
 *
 * [x] and [y] are written by nothing in arc 1 — they stay because the family's
 * table structure is the contract, whole. See the argument in [SoilSchema].
 */
@Entity(
    tableName = SoilSchema.TABLE,
    indices = [Index(value = ["parentId", "order", "deletedAt"], name = "idx_sketchbook_parent_order")],
)
data class SoilObjectEntity(
    @PrimaryKey val id: String,
    val parentId: String,
    val type: String,
    @ColumnInfo(name = "order", defaultValue = "0") val order: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val text: String? = null,
    val refId: String? = null,
    val x: Float? = null,
    val y: Float? = null,
    val width: Float? = null,
    val height: Float? = null,
    val color: String? = null,
    val strokeWidth: Float? = null,
    val style: String? = null,
    val flags: Int? = null,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val blob: ByteArray? = null,
)
