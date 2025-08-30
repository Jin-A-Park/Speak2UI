# Evaluation

## Dataset

- `eval/*`: Test set from [Voicify](https://github.com/vuminhduc796/Voicify/blob/main/parser/datasets/android_eval/test.txt).
- `eval_hard/*`: An additional test set created for further evaluation.

## Result

### 1. Voicify vs Speak2UI

Result on `eval/test_en` dataset.

| Model | EM Accuracy (%) | Target F1 (%) | Action F1 (%) | Inference Time (s) |
|:---:|:---:|:---:|:---:|:---:|
| Voicify | 91.09 | 93.05 | 97.03 | - |
| Ours (GPT-4o-mini) | **100** | **96.73** | **100** | **1.049** |
| Ours (GPT-5-mini) | 97.03 | 95.13 | 97.88 | 2.757 |

### 2. Additional Evaluation

Base Model: `GPT-4o-mini`

| Dataset | EM Accuracy (%) | Target F1 (%) | Action F1 (%) | Inference Time (s) |
|:---:|:---:|:---:|:---:|:---:|
| eval/test_en | 100 | 96.73 | 100 | 1.049 |
| eval/test_ko | 96.04 | 96.76 | 97.48 | 0.771 |
| eval_hard/test_en | 95.40 | 100 | 95.69 | 1.124 |
| eval_hard/test_ko | 97.70 | 100 | 97.76 | 0.779 |

## Translation Rules

- Tap: 탭해
- Press: 눌러
- Click: 클릭해
- Type: 타이핑해
- Enter: 입력해
- Go (to): 이동해
- Open: 열어
- Swipe: 밀어
- Scroll: 스크롤해
- Start: 시작해
- Check off: 선택 해제해
- Launch: 실행해
- Select: 선택해
- Double: 두 번
- Long: 길게
