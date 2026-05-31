-- V3__removing_country.sql
-- Removing the country field from exchange_rates table.
--
-- This is a destructive change. The country field is redundant since the currency_code already follows the "Country-Currency" 
-- format (e.g. "Canada-Dollar"). 
-- All existing data in this column will be lost, but it is not needed for any application functionality or queries. 
-- This simplifies the schema and eliminates potential inconsistencies between country and currency_code values.

ALTER TABLE exchange_rates
    DROP COLUMN IF EXISTS country;
