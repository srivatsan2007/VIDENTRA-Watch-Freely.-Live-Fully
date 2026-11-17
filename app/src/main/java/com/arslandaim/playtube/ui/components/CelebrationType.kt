/*
 * VIDENTRA - Watch Freely. Live Fully.
 * Licensed Under GPL-3.0+
 */
package com.arslandaim.playtube.ui.components

import java.util.Calendar

/**
 * Supported cultural, national, and special app celebrations.
 */
enum class CelebrationType(
    val id: String,
    val dateBadge: String,
    val title: String,
    val subtitle: String,
    val tributeMessage: String,
    val celebrationNote: String,
    val buttonText: String,
    val actionUrl: String? = null,
    val actionButtonText: String? = null,
    val easterEggTriggers: List<String>
) {
    NEW_YEAR(
        id = "new_year",
        dateBadge = "🎆 1st January • New Year Celebration",
        title = "Happy New Year! ✨",
        subtitle = "Welcome to a Brand New Year with Videntra",
        tributeMessage = "May this new year bring endless joy, inspiration, and boundless possibilities. Stream freely, explore without limits, and discover stories that move you.",
        celebrationNote = "Here's to 365 days of great stories, music, and freedom! 🥂🎉",
        buttonText = "Welcome New Year 🎈",
        easterEggTriggers = listOf("#newyear", "#happynewyear", "new year")
    ),

    PONGAL(
        id = "pongal",
        dateBadge = "🌾 14th - 15th January • Harvest Festival",
        title = "Happy Pongal & Makar Sankranti! 🍯",
        subtitle = "May Prosperity & Sweet Moments Overflow!",
        tributeMessage = "Pongalo Pongal! May the sweet boiling of milk and rice bring boundless prosperity, peace, and abundance to your home and loved ones.",
        celebrationNote = "Wishing you and your family a joyful, vibrant harvest season! 🪁🌾",
        buttonText = "Celebrate Pongal 🪁",
        easterEggTriggers = listOf("#pongal", "#makarsankranti", "pongal")
    ),

    REPUBLIC_DAY(
        id = "republic_day",
        dateBadge = "🇮🇳 26th January • Republic Day",
        title = "Happy Republic Day! 🇮🇳",
        subtitle = "Honoring the Constitution & the Spirit of India",
        tributeMessage = "On this historic day, we honor the visionary constitution and celebrate the world's largest democracy. Cherish the ideals of justice, liberty, equality, and fraternity.",
        celebrationNote = "Proudly uniting diverse cultures under one tricolor flag! 🇮🇳✨",
        buttonText = "Jai Hind! 🇮🇳",
        easterEggTriggers = listOf("#republicday", "#26january", "republic day")
    ),

    INDEPENDENCE_DAY(
        id = "independence_day",
        dateBadge = "🇮🇳 15th August • Independence Day",
        title = "Happy Independence Day! 🇮🇳",
        subtitle = "Watch Freely. Live Fully. Celebrating Freedom.",
        tributeMessage = "Saluting the bravery, vision, and sacrifices of the heroes who built our independent nation. Freedom is our greatest legacy and everyday inspiration.",
        celebrationNote = "Freedom to explore, freedom to learn, freedom to watch without limits! 🕊️🇮🇳",
        buttonText = "Celebrate Freedom 🕊️",
        easterEggTriggers = listOf("#independenceday", "#15august", "independence day")
    ),

    DEVELOPER_BIRTHDAY(
        id = "developer_birthday",
        dateBadge = "🎂 10th December • Special Celebration",
        title = "Happy Birthday, Srivatsan! 🎉",
        subtitle = "Celebrating the Creator of Videntra",
        tributeMessage = "Today marks a special milestone! Join the entire community in wishing Srivatsan a wonderful birthday.",
        celebrationNote = "Thank you for building Videntra to give everyone an ad-free, private, and seamless video streaming experience. May your year be filled with happiness, new innovations, and bug-free code! 🚀✨",
        buttonText = "Celebrate & Continue 🎈",
        actionUrl = "https://srivatsan-portfolio.vercel.app/",
        actionButtonText = "Send Birthday Wishes 💌",
        easterEggTriggers = listOf("#birthday", "#srivatsan", "srivatsan birthday", "december 10", "10 december", "#december10")
    );

    /**
     * Checks if current calendar matches the festival's celebration date.
     */
    fun isToday(calendar: Calendar = Calendar.getInstance()): Boolean {
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return when (this) {
            NEW_YEAR -> month == Calendar.JANUARY && day == 1
            PONGAL -> month == Calendar.JANUARY && (day == 14 || day == 15)
            REPUBLIC_DAY -> month == Calendar.JANUARY && day == 26
            INDEPENDENCE_DAY -> month == Calendar.AUGUST && day == 15
            DEVELOPER_BIRTHDAY -> month == Calendar.DECEMBER && day == 10
        }
    }
}
