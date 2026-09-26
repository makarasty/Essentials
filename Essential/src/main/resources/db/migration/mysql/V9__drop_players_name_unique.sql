SET @drop_name_unique = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'players' AND index_name = 'name' AND non_unique = 0) > 0,
    'ALTER TABLE players DROP INDEX name, ADD INDEX name (name)',
    'DO 0'
);
PREPARE drop_name_unique_stmt FROM @drop_name_unique;
EXECUTE drop_name_unique_stmt;
DEALLOCATE PREPARE drop_name_unique_stmt;

SET @add_name_index = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'players' AND index_name = 'name') = 0,
    'ALTER TABLE players ADD INDEX name (name)',
    'DO 0'
);
PREPARE add_name_index_stmt FROM @add_name_index;
EXECUTE add_name_index_stmt;
DEALLOCATE PREPARE add_name_index_stmt;
