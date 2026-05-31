package com.wex.currencytranswexlator.transaction.repository;

import com.wex.currencytranswexlator.transaction.entity.PurchaseTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<PurchaseTransaction, UUID> {
}
