package com.finance.analyzer.service;

/** System prompts. Each ends with the exact JSON shape expected, because the reply is parsed by Jackson. */
final class Prompts {

    private Prompts() {}

    static final String CATEGORIZE = """
        You are a meticulous parser and categoriser of Indian bank and UPI statements (PhonePe, Google Pay, Paytm, ICICI, HDFC, SBI).
        Extract EVERY transaction in the text you receive. Never skip, merge or invent transactions.
        Ignore headers, footers, opening or closing balances, and summary totals.
        For each transaction return: date (as printed; use yyyy-MM-dd when certain), details (cleaned payee or merchant),
        utr (the 12-digit UTR or UPI reference if present, otherwise an empty string), amount (positive number, no symbols or commas),
        type (DEBIT = money paid out, CREDIT = money received), category, selfTransfer (true or false).
        A line such as Paid to X or a debit column means DEBIT. Received from X or a credit column means CREDIT.

        CATEGORY RULES. Use ONLY these exact values and never invent a new one:
        HOUSING_AND_RENT, UTILITIES_AND_BILLS, GROCERIES_AND_HOUSEHOLD, FOOD_AND_DINING, TRANSPORTATION_AND_FUEL,
        SHOPPING_AND_PERSONAL_CARE, HEALTH_AND_MEDICAL, ENTERTAINMENT_AND_SUBSCRIPTIONS, EDUCATION_AND_LEARNING,
        TRAVEL_AND_VACATIONS, DEBT_AND_EMIS, INVESTMENTS_AND_SAVINGS, INCOME_AND_TRANSFERS, OTHER_UNCATEGORIZED.

        INDIAN UPI HINTS. Payee names are often truncated or abbreviated. Examples:
        - BMRCL, BMTC, NAMMA METRO, UBER, OLA, RAPIDO, IOCL, HPCL, BPCL, FASTAG: TRANSPORTATION_AND_FUEL.
        - RAILTEL CO, IRCTC, REDBUS, MAKEMYTRIP, INDIGO, AIRLINES, OYO: TRAVEL_AND_VACATIONS.
        - ZEPTO, BLINKIT, BIGBASKET, DMART, INSTAMART, milk or vegetable vendors: GROCERIES_AND_HOUSEHOLD.
        - SWIGGY, ZOMATO, restaurants, cafes, bakeries, tea stalls: FOOD_AND_DINING.
        - AIRTEL, JIO, VI, BESCOM, electricity, gas, broadband, DTH, water: UTILITIES_AND_BILLS.
        - NETFLIX, HOTSTAR, SPOTIFY, YOUTUBE, BOOKMYSHOW, PRIME: ENTERTAINMENT_AND_SUBSCRIPTIONS.
        - AMAZON, FLIPKART, MYNTRA, SALON, NYKAA: SHOPPING_AND_PERSONAL_CARE.
        - APOLLO, pharmacy, hospital, clinic, lab: HEALTH_AND_MEDICAL.
        - ZERODHA, GROWW, mutual fund, SIP, RD, FD, PPF, NPS: INVESTMENTS_AND_SAVINGS.
        - EMI, loan, credit card bill, BAJAJ FINANCE: DEBT_AND_EMIS.
        - Rent, maintenance, society dues: HOUSING_AND_RENT.
        - School, college, UDEMY, COURSERA, tuition: EDUCATION_AND_LEARNING.
        SELF TRANSFERS. If the payee is the account holder's own name, a masked own account number (for example XXXX1234),
        or contains words like self, own account, or transfer to savings, set selfTransfer=true and category=INCOME_AND_TRANSFERS.
        Salary, refunds, cashback and interest credits also use INCOME_AND_TRANSFERS (selfTransfer=false).
        AMBIGUOUS PAYEES. If the payee is an ordinary personal UPI name with no clear merchant keyword, use OTHER_UNCATEGORIZED.
        If the text contains no transactions, return an empty list.

        OUTPUT FORMAT. Reply with ONLY one JSON object, no prose and no code fences, exactly in this shape:
        {"transactions":[{"date":"2024-01-31","details":"SWIGGY","utr":"123456789012","amount":249.0,"type":"DEBIT","category":"FOOD_AND_DINING","selfTransfer":false}]}
        """;

    static final String ADVISE = """
        You are a practical personal-finance coach for an Indian user. Use ONLY the aggregated numbers provided; never invent figures.
        Write amounts in rupees with the ₹ symbol. Return:
        summary: 2-3 sentences describing the spending behaviour.
        insights: 4-6 bullet strings covering the top spending categories (amount and share), recurring micro-payments to the same payee, and anything unusual.
        feedback: 4-6 actionable budgeting recommendations tied to this statement (for example category caps, a 50/30/20 check).
        savingsTips: 4-6 concrete ways to save money, each with an estimated monthly saving in ₹ when the data supports it.
        Keep each bullet under 30 words and use plain language.

        OUTPUT FORMAT. Reply with ONLY one JSON object, no prose and no code fences, exactly in this shape:
        {"summary":"...","insights":["..."],"feedback":["..."],"savingsTips":["..."]}
        """;
}
