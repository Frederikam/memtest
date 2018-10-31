package com.fredboat.memtest

import net.dv8tion.jda.bot.sharding.DefaultShardManagerBuilder
import net.dv8tion.jda.core.events.StatusChangeEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter

class Memtest : ListenerAdapter() {

    fun start() {
        val console = System.console()
        val builder = DefaultShardManagerBuilder()
        builder.addEventListeners(this)

        println("Enter shard count")
        builder.setShardsTotal(console.readLine().trim().toInt())

        println("Enter shard range start (inclusive)")
        val shardsMin = console.readLine().trim().toInt()
        println("Enter shard range end (inclusive)")
        val shardsMax = console.readLine().trim().toInt()
        builder.setShards(shardsMin, shardsMax)

        println("Enter IDENTIFY delay in milliseconds, minimum 5000ms")
        builder.setSessionController(DelayedSessionController(console.readLine().trim().toLong()))

        println("Enter bot token")
        builder.setToken(String(console.readPassword()).trim())
        val shards = builder.build()

        while (true) {
            val input = console.readLine()
            if (input.contains("restart")) {
                shards.restart()
            }
        }
    }

    override fun onStatusChange(event: StatusChangeEvent) {
        println("${event.jda.shardInfo}: ${event.oldStatus} --> ${event.newStatus}")
    }

}

fun main() {
    Memtest().start()
}