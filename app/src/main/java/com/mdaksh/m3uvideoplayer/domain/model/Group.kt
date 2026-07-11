package com.mdaksh.m3uvideoplayer.domain.model

data class Group(
    val id: String, // Typically the group-title tag
    val playlistId: Long,
    val name: String,
    val type: GroupType = GroupType.LIVE
)

enum class GroupType {
    LIVE, MOVIE, SERIES
}
