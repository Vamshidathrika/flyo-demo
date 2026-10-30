package com.flyo.rescue;

import com.flyo.rescue.model.RebookOption;
import com.flyo.rescue.service.RankingEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RankingEngineTest {

    private final RankingEngine rankingEngine = new RankingEngine();

    @Test
    @DisplayName("Chunk 3 Deterministic Ranking: O2 is Rank 1 (saves connection), O3 is Rank 2, O1 is Rank 3 (disqualified)")
    void testDeterministicRankingOrder() {
        List<RebookOption> candidates = List.of(
                new RebookOption("O1", "C1", "FL201", "HYD → DEL", "Tomorrow 06:00", "Tomorrow 08:05", 85, 0, false, "", 0),
                new RebookOption("O2", "C1", "FL301", "HYD → BOM → DEL", "Tonight 23:30", "Tomorrow 01:40", 470, 4200, false, "", 0),
                new RebookOption("O3", "C1", "FL401", "HYD → DEL", "Tomorrow 10:00", "Tomorrow 12:15", -165, 1500, false, "", 0)
        );

        List<RebookOption> ranked = rankingEngine.rankOptions(candidates);

        assertEquals(3, ranked.size());

        // Rank 1: O2
        RebookOption rank1 = ranked.get(0);
        assertEquals("O2", rank1.getId());
        assertEquals(1, rank1.getRank());
        assertTrue(rank1.isSavesConnection());
        assertEquals(4200, rank1.getExtraCost());
        assertTrue(rank1.getReason().contains("Toronto (YYZ 09:30)"));

        // Rank 2: O3
        RebookOption rank2 = ranked.get(1);
        assertEquals("O3", rank2.getId());
        assertEquals(2, rank2.getRank());
        assertFalse(rank2.isSavesConnection());
        assertEquals(1500, rank2.getExtraCost());

        // Rank 3: O1 (Disqualified because connection buffer 85 min < 120 min)
        RebookOption rank3 = ranked.get(2);
        assertEquals("O1", rank3.getId());
        assertEquals(3, rank3.getRank());
        assertFalse(rank3.isSavesConnection());
        assertEquals(0, rank3.getExtraCost());
        assertTrue(rank3.getReason().contains("120 minutes"));
    }
}
