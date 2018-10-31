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
        builder.setShardsTotal(console.readLine().toInt())

        println("Enter shard range start (inclusive)")
        val shardsMin = console.readLine().toInt()
        println("Enter shard range end (inclusive)")
        val shardsMax = console.readLine().toInt()
        builder.setShards(shardsMin, shardsMax)

        println("Enter IDENTIFY delay in seconds, minimum 5 seconds")
        builder.setSessionController(DelayedSessionController(console.readLine().toLong()))

        println("Enter bot token")
        builder.setToken(String(console.readPassword()))
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