package com.site.xidong.questionSet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface QuestionSetRepository extends JpaRepository<QuestionSet, Long> {
    @Query("SELECT q FROM QuestionSet q WHERE q.isOpen = true")
    List<QuestionSet> findAllOpenQuestionSets();

    @Query("SELECT q FROM QuestionSet q WHERE q.siteUser.username = :username")
    List<QuestionSet> findMySets(String username);
}
