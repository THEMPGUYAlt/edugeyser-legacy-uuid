/*
 * Copyright (c) 2019-2024 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.session;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EducationUuidTest {

    private static final long EXPECTED_MSB = 0x0000000100000001L;

    private static final String TENANT_1 = "75535150-2dbb-4af5-9070-3fb6f6c8585c";
    private static final String TENANT_2 = "4cf5151d-0705-4be5-839d-fa2abe1b4206";
    private static final String USERNAME_1 = "JohnS";
    private static final String USERNAME_2 = "MaryJ";

    @Test
    void msbIsAlwaysFixed() {
        UUID uuid = GeyserSessionAdapter.createEducationUuid(TENANT_1, USERNAME_1);
        assertEquals(EXPECTED_MSB, uuid.getMostSignificantBits());
    }

    @Test
    void msbFixedAcrossInputs() {
        for (int i = 0; i < 100; i++) {
            String tenant = UUID.randomUUID().toString();
            String username = "user" + i;
            UUID uuid = GeyserSessionAdapter.createEducationUuid(tenant, username);
            assertEquals(EXPECTED_MSB, uuid.getMostSignificantBits(),
                    "MSB mismatch for tenant=" + tenant + " username=" + username);
        }
    }

    @Test
    void sameInputsProduceSameUuid() {
        UUID first = GeyserSessionAdapter.createEducationUuid(TENANT_1, USERNAME_1);
        UUID second = GeyserSessionAdapter.createEducationUuid(TENANT_1, USERNAME_1);
        assertEquals(first, second);
    }

    @Test
    void deterministicAcrossInvocations() {
        UUID expected = GeyserSessionAdapter.createEducationUuid(TENANT_1, USERNAME_1);
        for (int i = 0; i < 100; i++) {
            assertEquals(expected, GeyserSessionAdapter.createEducationUuid(TENANT_1, USERNAME_1));
        }
    }

    @Test
    void differentUsernamesProduceDifferentUuids() {
        UUID a = GeyserSessionAdapter.createEducationUuid(TENANT_1, USERNAME_1);
        UUID b = GeyserSessionAdapter.createEducationUuid(TENANT_1, USERNAME_2);
        assertNotEquals(a, b);
    }

    @Test
    void differentTenantsProduceDifferentUuids() {
        UUID a = GeyserSessionAdapter.createEducationUuid(TENANT_1, USERNAME_1);
        UUID b = GeyserSessionAdapter.createEducationUuid(TENANT_2, USERNAME_1);
        assertNotEquals(a, b);
    }

    @Test
    void noCollisionsAcrossManyInputs() {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 10000; i++) {
            String tenant = UUID.randomUUID().toString();
            String username = "user" + i;
            UUID uuid = GeyserSessionAdapter.createEducationUuid(tenant, username);
            assertTrue(seen.add(uuid), "Collision detected for tenant=" + tenant + " username=" + username);
        }
        assertEquals(10000, seen.size());
    }

    @Test
    void lsbDiffersFromMsb() {
        UUID uuid = GeyserSessionAdapter.createEducationUuid(TENANT_1, USERNAME_1);
        assertNotEquals(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
    }
}
