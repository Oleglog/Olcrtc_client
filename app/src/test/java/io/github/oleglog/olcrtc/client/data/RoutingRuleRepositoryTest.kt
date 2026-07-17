package io.github.oleglog.olcrtc.client.data

import io.github.oleglog.olcrtc.client.routing.RoutingRule
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingRuleRepositoryTest {
    @Test
    fun keepsCreationOrderAndSupportsToggleEditAndDelete() {
        val dao = FakeRoutingRuleDao()
        val repository = RoutingRuleRepository(dao)
        val first = repository.save(rule("first.example", sortOrder = 0))
        val second = repository.save(rule("second.example", sortOrder = 1))

        assertEquals(listOf(first, second), repository.getAll().map(RoutingRule::id))

        repository.setEnabled(first, false)
        assertEquals(false, repository.getAll().first().enabled)
        assertEquals(listOf(second), repository.getEnabled().map(RoutingRule::id))

        repository.save(rule("second.example", id = second, sortOrder = 1, action = RoutingRule.Action.BLOCK))
        assertEquals(RoutingRule.Action.BLOCK, repository.getAll().last().action)

        repository.delete(first)
        assertEquals(listOf(second), repository.getAll().map(RoutingRule::id))
    }

    private fun rule(
        value: String,
        id: Long = 0,
        sortOrder: Int,
        action: RoutingRule.Action = RoutingRule.Action.VPN,
    ) = RoutingRule.create(
        id = id,
        matchType = RoutingRule.MatchType.DOMAIN,
        value = value,
        action = action,
        sortOrder = sortOrder,
    )

    private class FakeRoutingRuleDao : RoutingRuleDao {
        private val values = mutableListOf<RoutingRuleEntity>()
        private var nextId = 1L

        override fun getAll(): List<RoutingRuleEntity> = values.sortedWith(
            compareBy(RoutingRuleEntity::sortOrder).thenBy(RoutingRuleEntity::id),
        )

        override fun getEnabled(): List<RoutingRuleEntity> = getAll().filter(RoutingRuleEntity::enabled)

        override fun find(matchType: String, value: String): RoutingRuleEntity? =
            values.firstOrNull { it.matchType == matchType && it.value == value }

        override fun insert(rule: RoutingRuleEntity): Long = nextId++.also { values += rule.copy(id = it) }

        override fun update(rule: RoutingRuleEntity): Int {
            val index = values.indexOfFirst { it.id == rule.id }
            if (index < 0) return 0
            values[index] = rule
            return 1
        }

        override fun setEnabled(id: Long, enabled: Boolean): Int {
            val current = values.firstOrNull { it.id == id } ?: return 0
            return update(current.copy(enabled = enabled))
        }

        override fun delete(id: Long): Int = if (values.removeAll { it.id == id }) 1 else 0
    }
}
