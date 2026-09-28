package com.payguard.transaction.feature;

import com.payguard.transaction.Transaction;
import com.payguard.transaction.TransactionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class BehavioralFeatureService {

    private static final BigDecimal MAX_AMOUNT_RATIO = new BigDecimal("999999.9999");
    private static final BigDecimal ZERO_AVG = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal ZERO_RATIO = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

    private final TransactionRepository transactionRepository;
    private final String businessZoneId;

    public BehavioralFeatureService(TransactionRepository transactionRepository,
                                  @Value("${payguard.business-zone:Asia/Kolkata}") String businessZoneId) {
        this.transactionRepository = transactionRepository;
        this.businessZoneId = businessZoneId;
    }

    public BehavioralFeatureResult calculateFeatures(Transaction currentTransaction) {
        String accountId = currentTransaction.getAccountId();
        UUID currentId = currentTransaction.getId();
        OffsetDateTime currentTimestamp = currentTransaction.getTransactionTimestamp();
        BigDecimal currentAmount = currentTransaction.getAmount();
        String currentDeviceId = currentTransaction.getDeviceId();
        String currentLocation = currentTransaction.getLocation();

        // A. transactions_last_2_min
        OffsetDateTime twoMinutesAgo = currentTimestamp.minusMinutes(2);
        long count2Min = transactionRepository.countPreviousTransactionsInRange(
                accountId, currentId, twoMinutesAgo, currentTimestamp);
        int transactionsLast2Min = (int) count2Min;

        // B. transactions_last_1_hour
        OffsetDateTime oneHourAgo = currentTimestamp.minusHours(1);
        long count1Hour = transactionRepository.countPreviousTransactionsInRange(
                accountId, currentId, oneHourAgo, currentTimestamp);
        int transactionsLast1Hour = (int) count1Hour;

        // C. account_avg_amount
        BigDecimal rawAvg = transactionRepository.findHistoricalAverageAmount(
                accountId, currentId, currentTimestamp);
        BigDecimal accountAvgAmount;
        if (rawAvg == null) {
            accountAvgAmount = ZERO_AVG;
        } else {
            accountAvgAmount = rawAvg.setScale(2, RoundingMode.HALF_UP);
        }

        // D. amount_ratio
        BigDecimal amountRatio;
        if (accountAvgAmount.compareTo(BigDecimal.ZERO) == 0) {
            amountRatio = ZERO_RATIO;
        } else {
            BigDecimal ratio = currentAmount.divide(accountAvgAmount, 4, RoundingMode.HALF_UP);
            if (ratio.compareTo(MAX_AMOUNT_RATIO) > 0) {
                ratio = MAX_AMOUNT_RATIO;
            }
            amountRatio = ratio;
        }

        // E. time_since_previous_transaction_seconds & previous existence check
        List<Transaction> previousList = transactionRepository.findPreviousTransactions(
                accountId, currentId, currentTimestamp, PageRequest.of(0, 1));
        boolean hasPrevious = !previousList.isEmpty();
        Long timeSincePreviousTransactionSeconds = null;
        if (hasPrevious) {
            Transaction previousTxn = previousList.get(0);
            long seconds = ChronoUnit.SECONDS.between(previousTxn.getTransactionTimestamp(), currentTimestamp);
            timeSincePreviousTransactionSeconds = Math.max(0L, seconds);
        }

        // F. new_device
        boolean deviceUsedBefore = transactionRepository.isDevicePreviouslyUsed(
                accountId, currentId, currentDeviceId, currentTimestamp);
        boolean newDevice = !deviceUsedBefore;

        // G. new_location
        boolean locationUsedBefore = transactionRepository.isLocationPreviouslyUsed(
                accountId, currentId, currentLocation, currentTimestamp);
        boolean newLocation = !locationUsedBefore;

        // H. transaction_hour
        ZoneId zoneId = ZoneId.of(businessZoneId);
        ZonedDateTime zdt = currentTimestamp.atZoneSameInstant(zoneId);
        short transactionHour = (short) zdt.getHour();

        // I. odd_hour
        boolean oddHour = (transactionHour >= 23 || transactionHour < 6);

        return new BehavioralFeatureResult(
                transactionsLast2Min,
                transactionsLast1Hour,
                accountAvgAmount,
                amountRatio,
                timeSincePreviousTransactionSeconds,
                newDevice,
                newLocation,
                transactionHour,
                oddHour,
                hasPrevious
        );
    }
}
