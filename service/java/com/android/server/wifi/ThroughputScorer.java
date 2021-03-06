/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi;

import static com.android.server.wifi.WifiNetworkSelector.NetworkNominator.NOMINATOR_ID_SCORED;

import android.annotation.NonNull;
import android.util.Log;

import com.android.server.wifi.WifiCandidates.Candidate;
import com.android.server.wifi.WifiCandidates.ScoredCandidate;

import java.util.Collection;

/**
 * A candidate scorer that combines RSSI base score and network throughput score.
 */
final class ThroughputScorer implements WifiCandidates.CandidateScorer {
    private static final String TAG = "ThroughputScorer";
    private static final boolean DBG = false;
    /**
     * This should match WifiNetworkSelector.experimentIdFromIdentifier(getIdentifier())
     * when using the default ScoringParams.
     */
    public static final int THROUGHPUT_SCORER_DEFAULT_EXPID = 42330058;

    /**
     * Base score that is large enough to override all of the other categories.
     * This is applied to the last-select network for a limited duration.
     */
    public static final int TOP_TIER_BASE_SCORE = 1_000_000;

    private final ScoringParams mScoringParams;

    // config_wifi_framework_RSSI_SCORE_OFFSET
    public static final int RSSI_SCORE_OFFSET = 85;

    // config_wifi_framework_RSSI_SCORE_SLOPE
    public static final int RSSI_SCORE_SLOPE_IS_4 = 4;

    /**
     * Sample scoring buckets (assumes default overlay bucket sizes for metered, saved, etc):
     * 0 -> 500: OEM private
     * 500 -> 1000: OEM paid
     * 1000 -> 1500: untrusted 3rd party
     * 1500 -> 2000: untrusted carrier
     * 2000 -> 2500: metered suggestions
     * 2500 -> 3000: metered saved
     * 3000 -> 3500: unmetered suggestions
     * 3500 -> 4000: unmetered saved
     */
    public static final int TRUSTED_AWARD = 1000;
    public static final int HALF_TRUSTED_AWARD = 1000 / 2;
    public static final int NOT_OEM_PAID_AWARD = 500;
    public static final int NOT_OEM_PRIVATE_AWARD = 500;

    private static final boolean USE_USER_CONNECT_CHOICE = true;

    ThroughputScorer(ScoringParams scoringParams) {
        mScoringParams = scoringParams;
    }

    @Override
    public String getIdentifier() {
        return "ThroughputScorer";
    }

    /**
     * Calculates an individual candidate's score.
     */
    private ScoredCandidate scoreCandidate(Candidate candidate, boolean currentNetworkHasInternet) {
        int rssiSaturationThreshold = mScoringParams.getSufficientRssi(candidate.getFrequency());
        int rssi = Math.min(candidate.getScanRssi(), rssiSaturationThreshold);
        int rssiBaseScore = (rssi + RSSI_SCORE_OFFSET) * RSSI_SCORE_SLOPE_IS_4;

        int throughputBonusScore = calculateThroughputBonusScore(candidate);

        int rssiAndThroughputScore = rssiBaseScore + throughputBonusScore;

        boolean unExpectedNoInternet = candidate.hasNoInternetAccess()
                && !candidate.isNoInternetAccessExpected();
        int currentNetworkBonusMin = mScoringParams.getCurrentNetworkBonusMin();
        int currentNetworkBonus = Math.max(currentNetworkBonusMin, rssiAndThroughputScore
                * mScoringParams.getCurrentNetworkBonusPercent() / 100);
        int currentNetworkBoost = (candidate.isCurrentNetwork() && !unExpectedNoInternet)
                ? currentNetworkBonus : 0;

        int securityAward = candidate.isOpenNetwork()
                ? 0
                : mScoringParams.getSecureNetworkBonus();

        int unmeteredAward = candidate.isMetered()
                ? 0
                : mScoringParams.getUnmeteredNetworkBonus();

        int savedNetworkAward = candidate.isEphemeral() ? 0 : mScoringParams.getSavedNetworkBonus();

        int trustedAward = TRUSTED_AWARD;
        if (!candidate.isTrusted()) {
            savedNetworkAward = 0; // Saved networks are not untrusted, but clear anyway
            unmeteredAward = 0; // Ignore metered for untrusted networks
            if (candidate.isCarrierOrPrivileged()) {
                trustedAward = HALF_TRUSTED_AWARD;
            } else if (candidate.getNominatorId() == NOMINATOR_ID_SCORED) {
                Log.e(TAG, "ScoredNetworkNominator is not carrier or privileged!");
                trustedAward = 0;
            } else {
                trustedAward = 0;
            }
        }

        int notOemPaidAward = NOT_OEM_PAID_AWARD;
        if (candidate.isOemPaid()) {
            savedNetworkAward = 0; // Saved networks are not oem paid, but clear anyway
            unmeteredAward = 0; // Ignore metered for oem paid networks
            trustedAward = 0; // Ignore untrusted for oem paid networks.
            notOemPaidAward = 0;
        }

        int notOemPrivateAward = NOT_OEM_PRIVATE_AWARD;
        if (candidate.isOemPrivate()) {
            savedNetworkAward = 0; // Saved networks are not oem paid, but clear anyway
            unmeteredAward = 0; // Ignore metered for oem paid networks
            trustedAward = 0; // Ignore untrusted for oem paid networks.
            notOemPaidAward = 0;
            notOemPrivateAward = 0;
        }

        int score = rssiBaseScore + throughputBonusScore
                + currentNetworkBoost + securityAward + unmeteredAward + savedNetworkAward
                + trustedAward + notOemPaidAward + notOemPrivateAward;

        // do not select a network that has no internet when the current network has internet.
        if (currentNetworkHasInternet && !candidate.isCurrentNetwork() && unExpectedNoInternet) {
            score = 0;
        }

        if (candidate.getLastSelectionWeight() > 0.0) {
            // Put a recently-selected network in a tier above everything else,
            // but include rssi and throughput contributions for BSSID selection.
            score = TOP_TIER_BASE_SCORE + rssiBaseScore + throughputBonusScore;
        }

        if (DBG) {
            Log.d(TAG, " rssiScore: " + rssiBaseScore
                    + " throughputScore: " + throughputBonusScore
                    + " currentNetworkBoost: " + currentNetworkBoost
                    + " securityAward: " + securityAward
                    + " unmeteredAward: " + unmeteredAward
                    + " savedNetworkAward: " + savedNetworkAward
                    + " trustedAward: " + trustedAward
                    + " notOemPaidAward: " + notOemPaidAward
                    + " notOemPrivateAward: " + notOemPrivateAward
                    + " final score: " + score);
        }

        // The old method breaks ties on the basis of RSSI, which we can
        // emulate easily since our score does not need to be an integer.
        double tieBreaker = candidate.getScanRssi() / 1000.0;
        return new ScoredCandidate(score + tieBreaker, 10,
                USE_USER_CONNECT_CHOICE, candidate);
    }

    private int calculateThroughputBonusScore(Candidate candidate) {
        int throughputScoreRaw = candidate.getPredictedThroughputMbps()
                * mScoringParams.getThroughputBonusNumerator()
                / mScoringParams.getThroughputBonusDenominator();
        return Math.min(throughputScoreRaw, mScoringParams.getThroughputBonusLimit());
    }

    private boolean doesAnyCurrentNetworksHaveInternet(@NonNull Collection<Candidate> candidates) {
        for (Candidate candidate : candidates) {
            if (candidate.isCurrentNetwork() && !candidate.hasNoInternetAccess()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public ScoredCandidate scoreCandidates(@NonNull Collection<Candidate> candidates) {
        ScoredCandidate choice = ScoredCandidate.NONE;
        boolean currentNetworkHasInternet = doesAnyCurrentNetworksHaveInternet(candidates);
        for (Candidate candidate : candidates) {
            ScoredCandidate scoredCandidate = scoreCandidate(candidate, currentNetworkHasInternet);
            if (scoredCandidate.value > choice.value) {
                choice = scoredCandidate;
            }
        }
        // Here we just return the highest scored candidate; we could
        // compute a new score, if desired.
        return choice;
    }

}
