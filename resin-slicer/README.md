# resinslicer (work in progress)

Resin (MSLA) slicer that recommends print settings, auto-generates supports,
and drills drain holes into peg holes / sealed voids that would trap resin.

```
pip install -r requirements.txt
python examples/make_test_models.py examples
python -m resinslicer profiles
python -m resinslicer analyze examples/miniature_pegs.stl -o out   # supports + settings + STL + preview
python -m resinslicer slice   examples/miniature_pegs.stl -o out   # also writes layer images (.sl1)
```

## Status
- Done: peg-hole/void detection, cup-aware drain drilling, auto-orientation,
  supports with island check, settings recommender, STL + HTML preview export.
- To do: speed up full-resolution slicing (a 4K slice of the hollow test
  model took >2 min), check the .sl1 output in UVtools, add tests.
