#!/usr/bin/env python3
"""
PrivacyPilot evaluation harness.

Runs the two classification-style AI functions against a hand-labeled set and
reports accuracy and a confusion matrix, writing results to eval/results.json.

IMPORTANT — what these numbers measure:
By default PrivacyPilot runs its *deterministic offline fallback* (no LLM API
key required). This script re-implements that exact fallback logic in Python so
the committed numbers are reproducible by anyone, with no API key and no network.
The Java services (PolicyExtractorService.fallback and
ResponseClassifierService.fallback) use the same rules. When a real LLM key is
configured, the Java services prefer the LLM and only fall back on failure, so
production accuracy may differ — these numbers are the guaranteed-reproducible
deterministic baseline, not a claim about a specific LLM.
"""

import json
import os
import re
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))

CONFIDENCE_THRESHOLD = 0.6  # mirrors privacypilot.classifier.confidence-threshold

CATEGORIES = [
    "RECEIVED", "COMPLETED", "VERIFICATION_REQUIRED",
    "MORE_INFORMATION_REQUIRED", "REJECTED", "UNKNOWN",
]


# --- Policy extractor fallback (mirrors PolicyExtractorService.fallback) ---
def policy_deletion_supported(text: str) -> bool:
    lower = text.lower()
    markers = [
        "delete", "deletion", "erase", "erasure",
        "remove your", "removal of your", "removal request",
        "removed from", "right to be forgotten",
    ]
    if not any(m in lower for m in markers):
        return False
    # Negation guard: deletion is mentioned but explicitly refused.
    denials = [
        "do not delete", "does not delete", "cannot delete", "will not delete",
        "do not erase", "not able to delete", "no right to erasure",
    ]
    if any(d in lower for d in denials):
        return False
    return True


# --- Response classifier fallback (mirrors ResponseClassifierService.fallback) ---
def classify_reply(body: str):
    t = body.lower()

    completed = any(s in t for s in [
        "deleted", "has been removed", "erased", "completed your request"])
    rejected = any(s in t for s in [
        "cannot", "unable to", "denied", "we will not", "legal obligation to retain"])
    verify = any(s in t for s in [
        "verify your identity", "confirm your identity", "proof of"])
    more_info = any(s in t for s in [
        "provide your", "need more information", "additional information", "account number"])
    received = any(s in t for s in [
        "received your request", "we have received", "ticket", "reference number"])

    signals = sum([completed, rejected, verify, more_info, received])

    # Conflicting (>=2) or no signal -> ambiguous / low confidence.
    if signals >= 2 or signals == 0:
        return "UNKNOWN", 0.3, True
    if completed:
        return "COMPLETED", 0.7, False
    if rejected:
        return "REJECTED", 0.7, False
    if verify:
        return "VERIFICATION_REQUIRED", 0.7, False
    if more_info:
        return "MORE_INFORMATION_REQUIRED", 0.7, False
    return "RECEIVED", 0.65, False


def route(classification: str, confidence: float, ambiguous: bool) -> str:
    """Apply the same routing rule the workflow uses: low confidence or ambiguity
    or UNKNOWN => human review (reported here as effective label UNKNOWN)."""
    if ambiguous or confidence < CONFIDENCE_THRESHOLD or classification == "UNKNOWN":
        return "UNKNOWN"
    return classification


def load(name):
    with open(os.path.join(HERE, name)) as f:
        return json.load(f)["items"]


def eval_policies():
    items = load("policies.json")
    tp = tn = fp = fn = 0
    errors = []
    for it in items:
        pred = policy_deletion_supported(it["text"])
        truth = it["label"]["deletionSupported"]
        if pred and truth:
            tp += 1
        elif not pred and not truth:
            tn += 1
        elif pred and not truth:
            fp += 1
            errors.append({"id": it["id"], "predicted": pred, "expected": truth})
        else:
            fn += 1
            errors.append({"id": it["id"], "predicted": pred, "expected": truth})
    total = len(items)
    correct = tp + tn
    precision = tp / (tp + fp) if (tp + fp) else 0.0
    recall = tp / (tp + fn) if (tp + fn) else 0.0
    f1 = (2 * precision * recall / (precision + recall)) if (precision + recall) else 0.0
    return {
        "total": total,
        "correct": correct,
        "accuracy": round(correct / total, 4) if total else 0.0,
        "confusion": {"tp": tp, "tn": tn, "fp": fp, "fn": fn},
        "precision_deletion_supported": round(precision, 4),
        "recall_deletion_supported": round(recall, 4),
        "f1_deletion_supported": round(f1, 4),
        "errors": errors,
    }


def empty_matrix():
    return OrderedDict((a, OrderedDict((b, 0) for b in CATEGORIES)) for a in CATEGORIES)


def eval_replies():
    items = load("replies.json")
    matrix = empty_matrix()  # matrix[expected][predicted]
    correct = 0
    errors = []
    for it in items:
        cls, conf, amb = classify_reply(it["body"])
        pred = route(cls, conf, amb)
        truth = it["label"]["classification"]
        matrix[truth][pred] += 1
        if pred == truth:
            correct += 1
        else:
            errors.append({
                "id": it["id"], "expected": truth, "predicted": pred,
                "raw_classification": cls, "confidence": conf, "ambiguous": amb,
            })
    total = len(items)
    return {
        "total": total,
        "correct": correct,
        "accuracy": round(correct / total, 4) if total else 0.0,
        "confusion_matrix": matrix,
        "errors": errors,
        "note": "predicted label is post-routing: low-confidence/ambiguous replies become UNKNOWN (human review).",
    }


def main():
    results = {
        "meta": {
            "harness": "eval/run_eval.py",
            "mode": "deterministic-fallback",
            "confidence_threshold": CONFIDENCE_THRESHOLD,
            "categories": CATEGORIES,
            "note": "Measures the deterministic offline fallback that runs with no LLM API key. Reproducible with: python3 eval/run_eval.py",
        },
        "policy_extractor": eval_policies(),
        "response_classifier": eval_replies(),
    }

    out = os.path.join(HERE, "results.json")
    with open(out, "w") as f:
        json.dump(results, f, indent=2)

    # Human-readable summary.
    p = results["policy_extractor"]
    r = results["response_classifier"]
    print("=== PrivacyPilot Eval (deterministic fallback) ===")
    print(f"Policy extractor:   {p['correct']}/{p['total']} correct "
          f"(accuracy {p['accuracy']:.2%}, F1 {p['f1_deletion_supported']:.2f})")
    print(f"Response classifier: {r['correct']}/{r['total']} correct "
          f"(accuracy {r['accuracy']:.2%})")
    print()
    print("Confusion matrix (rows = expected, cols = predicted):")
    header = "expected \\ pred | " + " ".join(f"{c[:6]:>6}" for c in CATEGORIES)
    print(header)
    for exp in CATEGORIES:
        row = r["confusion_matrix"][exp]
        print(f"{exp[:15]:<15} | " + " ".join(f"{row[c]:>6}" for c in CATEGORIES))
    print()
    if r["errors"]:
        print("Misclassified replies:")
        for e in r["errors"]:
            print(f"  {e['id']}: expected {e['expected']}, got {e['predicted']} "
                  f"(raw={e['raw_classification']}, conf={e['confidence']}, ambiguous={e['ambiguous']})")
    if p["errors"]:
        print("Misclassified policies:")
        for e in p["errors"]:
            print(f"  {e['id']}: expected {e['expected']}, got {e['predicted']}")
    print(f"\nWrote {out}")


if __name__ == "__main__":
    main()
